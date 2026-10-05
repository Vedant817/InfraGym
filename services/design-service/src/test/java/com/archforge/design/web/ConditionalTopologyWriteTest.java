package com.archforge.design.web;

import com.archforge.design.canvas.CanvasRepository;
import com.archforge.design.domain.DesignEntity;
import com.archforge.design.repository.DesignRepository;
import com.archforge.design.service.SubmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.MongoDBContainer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP contract exercised against real MongoDB, including competing database writes. */
class ConditionalTopologyWriteTest {
    private static MongoDBContainer container;
    private static MongoClient client;
    private static MongoTemplate mongo;
    private static final String OWNER = "revision-test-owner";
    private static final UsernamePasswordAuthenticationToken AUTH =
            new UsernamePasswordAuthenticationToken(OWNER, "unused");
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    private MockMvc mvc;
    private CanvasRepository canvases;

    @BeforeAll
    static void database() {
        // CI defaults to an isolated container; local verification may reuse the existing
        // broker/database fleet. In both cases only this random test database is dropped.
        String uri = System.getProperty("archforge.test.mongo-uri");
        if (uri == null) {
            container = new MongoDBContainer("mongo:7.0");
            container.start();
            uri = container.getReplicaSetUrl();
        }
        client = MongoClients.create(uri);
        mongo = new MongoTemplate(client, "archforge_revision_test_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterAll
    static void cleanup() {
        if (mongo != null) mongo.getDb().drop();
        if (client != null) client.close();
        if (container != null) container.stop();
    }

    @BeforeEach
    void setup() {
        mongo.getDb().getCollection("canvas_documents").drop();
        canvases = new CanvasRepository(mongo);
        DesignRepository designs = mock(DesignRepository.class);
        when(designs.save(any(DesignEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(designs.findByIdAndUserId(any(UUID.class), any(String.class))).thenAnswer(invocation -> {
            String owner = invocation.getArgument(1);
            return OWNER.equals(owner) ? Optional.of(DesignEntity.create(OWNER, "CAS test", "", "normal", 0, 0))
                    : Optional.empty();
        });
        mvc = MockMvcBuilders.standaloneSetup(new DesignController(designs, canvases,
                        mock(SubmissionService.class), mapper))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    private String create() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/designs").principal(AUTH)
                        .contentType("application/json").content("{\"name\":\"CAS test\",\"topology\":{\"nodes\":[],\"edges\":[]}}"))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.revision").value(1)).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String body(String node) throws Exception {
        return mapper.writeValueAsString(Map.of("topology", Map.of("nodes",
                java.util.List.of(Map.of("id", node, "type", "service", "data", Map.of("label", node))), "edges", java.util.List.of())));
    }

    private MvcResult save(String id, long revision, String node) throws Exception {
        return mvc.perform(put("/api/v1/designs/{id}/topology", id).principal(AUTH)
                .header("If-Match", "\"" + revision + "\"")
                .contentType("application/json").content(body(node))).andReturn();
    }

    @Test
    void firstSaveIncrementsAndReadReturnsTheEtag() throws Exception {
        String id = create();
        assertThat(save(id, 1, "first").getResponse().getStatus()).isEqualTo(200);
        mvc.perform(get("/api/v1/designs/{id}", id).principal(AUTH))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.topology.nodes[0].id").value("first"));
    }

    @Test
    void correctCurrentRevisionCanBeSavedAgain() throws Exception {
        String id = create();
        assertThat(save(id, 1, "first").getResponse().getStatus()).isEqualTo(200);
        var second = save(id, 2, "second").getResponse();
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(second.getHeader("ETag")).isEqualTo("\"3\"");
        assertThat(mapper.readTree(second.getContentAsString()).get("revision").asLong()).isEqualTo(3);
        assertThat(canvases.findByDesignIdAndUserId(id, OWNER).orElseThrow().getTopology().nodes().getFirst().get("id"))
                .isEqualTo("second");
    }

    @Test
    void staleWriteReportsStoredRevisionAndLeavesDocumentUntouched() throws Exception {
        String id = create();
        assertThat(save(id, 1, "winner").getResponse().getStatus()).isEqualTo(200);
        var before = canvases.findByDesignIdAndUserId(id, OWNER).orElseThrow();
        var rejected = save(id, 1, "loser").getResponse();
        assertThat(rejected.getStatus()).isEqualTo(409);
        var conflict = mapper.readTree(rejected.getContentAsString());
        assertThat(conflict.get("storedRevision").asLong()).isEqualTo(2);
        assertThat(conflict.get("expectedRevision").asLong()).isEqualTo(1);
        assertThat(conflict.get("reloadRequired").asBoolean()).isTrue();
        assertThat(conflict.get("detail").asText()).contains("Reload");
        var after = canvases.findByDesignIdAndUserId(id, OWNER).orElseThrow();
        assertThat(after.getTopology()).isEqualTo(before.getTopology());
        assertThat(after.getRevision()).isEqualTo(before.getRevision());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    }

    @Test
    void twoConcurrentWritesHaveExactlyOneWinner() throws Exception {
        String id = create();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { ready.countDown(); start.await(10, TimeUnit.SECONDS); return save(id, 1, "a"); });
            var b = pool.submit(() -> { ready.countDown(); start.await(10, TimeUnit.SECONDS); return save(id, 1, "b"); });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var first = a.get(20, TimeUnit.SECONDS).getResponse();
            var second = b.get(20, TimeUnit.SECONDS).getResponse();
            assertThat(java.util.List.of(first.getStatus(), second.getStatus())).containsExactlyInAnyOrder(200, 409);
            var winner = mapper.readTree((first.getStatus() == 200 ? first : second).getContentAsString());
            var stored = canvases.findByDesignIdAndUserId(id, OWNER).orElseThrow();
            assertThat(stored.getRevision()).isEqualTo(2);
            assertThat(stored.getTopology().nodes().getFirst().get("id")).isEqualTo(winner.at("/topology/nodes/0/id").asText());
        }
    }

    @Test
    void missingConditionalHeaderIs428AndInvalidHeadersAre400() throws Exception {
        String id = create();
        mvc.perform(put("/api/v1/designs/{id}/topology", id).principal(AUTH)
                        .contentType("application/json").content(body("missing")))
                .andExpect(status().is(428));
        for (String invalid : java.util.List.of("1", "*", "W/\"1\"", "\"1\", \"2\"", "\"-1\"", "\"99999999999999999999\"")) {
            mvc.perform(put("/api/v1/designs/{id}/topology", id).principal(AUTH).header("If-Match", invalid)
                    .contentType("application/json").content(body("invalid"))).andExpect(status().isBadRequest());
        }
        assertThat(canvases.findByDesignIdAndUserId(id, OWNER).orElseThrow().getRevision()).isEqualTo(1);
    }

    @Test
    void missingAndForeignDocumentsCannotBeUpserted() throws Exception {
        String id = create();
        var before = canvases.findByDesignIdAndUserId(id, OWNER).orElseThrow();
        assertThat(canvases.compareAndSetRevision(id, "other-owner", 1, before.getTopology())).isEmpty();
        assertThat(canvases.compareAndSetRevision(UUID.randomUUID().toString(), OWNER, 1, before.getTopology())).isEmpty();
        assertThat(mongo.getDb().getCollection("canvas_documents").countDocuments()).isEqualTo(1);
        assertThat(canvases.findByDesignIdAndUserId(id, OWNER).orElseThrow().getRevision()).isEqualTo(1);
    }
}
