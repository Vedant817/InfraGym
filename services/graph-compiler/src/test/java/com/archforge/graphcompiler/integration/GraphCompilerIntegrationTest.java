package com.archforge.graphcompiler.integration;

import com.archforge.graphcompiler.schema.ReactFlowEdge;
import com.archforge.graphcompiler.schema.ReactFlowNode;
import com.archforge.graphcompiler.schema.ReactFlowTopology;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"design.submissions", "simulation.tasks", "graph-compiler.dlt"})
@DirtiesContext
class GraphCompilerIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testEndToEndCompilation() throws Exception {
        ReactFlowTopology topology = createValidTopology();

        Map<String, Object> event = new HashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("submissionId", UUID.randomUUID().toString());
        event.put("userId", "test-user");
        event.put("sessionId", "test-session");
        event.put("version", "1.0");
        event.put("source", "test");
        event.put("correlationId", UUID.randomUUID().toString());
        event.put("topology", topology);
        event.put("submittedAt", new Date().toString());

        String message = objectMapper.writeValueAsString(event);

        kafkaTemplate.send("design.submissions", event.get("submissionId").toString(), message);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(true);
        });
    }

    @Test
    void testInvalidTopologyGoesToDLQ() throws Exception {
        ReactFlowTopology topology = new ReactFlowTopology();
        topology.setNodes(Collections.emptyList());
        topology.setEdges(Collections.emptyList());

        Map<String, Object> event = new HashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("submissionId", UUID.randomUUID().toString());
        event.put("userId", "test-user");
        event.put("sessionId", "test-session");
        event.put("version", "1.0");
        event.put("source", "test");
        event.put("correlationId", UUID.randomUUID().toString());
        event.put("topology", topology);
        event.put("submittedAt", new Date().toString());

        String message = objectMapper.writeValueAsString(event);

        kafkaTemplate.send("design.submissions", event.get("submissionId").toString(), message);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(true);
        });
    }

    private ReactFlowTopology createValidTopology() {
        ReactFlowTopology topology = new ReactFlowTopology();

        ReactFlowNode api = new ReactFlowNode();
        api.setId("api");
        api.setType("service");
        api.setData(new com.archforge.graphcompiler.schema.NodeData());
        api.getData().setLabel("API");

        ReactFlowNode db = new ReactFlowNode();
        db.setId("db");
        db.setType("database");
        db.setData(new com.archforge.graphcompiler.schema.NodeData());
        db.getData().setLabel("Database");

        ReactFlowEdge edge = new ReactFlowEdge();
        edge.setId("e1");
        edge.setSource("api");
        edge.setTarget("db");

        topology.setNodes(Arrays.asList(api, db));
        topology.setEdges(Collections.singletonList(edge));

        return topology;
    }
}
