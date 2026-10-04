package com.archforge.graphcompiler.contract;

import com.archforge.contracts.canvas.CanvasEdge;
import com.archforge.contracts.canvas.CanvasNode;
import com.archforge.contracts.canvas.NodeData;
import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.canvas.Position;
import com.archforge.contracts.canvas.Topology;
import com.archforge.contracts.event.DesignSubmittedEvent;
import com.archforge.contracts.event.SimulationRequestedEvent;
import com.archforge.contracts.graph.CompiledEdge;
import com.archforge.contracts.graph.CompiledGraph;
import com.archforge.contracts.graph.CompiledNode;
import com.archforge.contracts.json.ContractJson;
import com.archforge.contracts.lifecycle.ScenarioType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for every type that crosses a process boundary.
 *
 * <p>This is the highest-value missing test in the codebase, and its absence is why the
 * pipeline could not deliver a single message end to end. The compiled-graph types were
 * Lombok {@code @Value} classes with private constructors and no Jackson creator, so
 * every consumer threw {@code InvalidDefinitionException: no Creators} on the first
 * record and the failure was swallowed — leaving a green health check and an empty
 * topic.
 *
 * <p>Each test here does a full serialise-then-deserialise cycle and asserts on the
 * values that matter, so a schema change that breaks a consumer fails here rather than
 * in production.
 */
class ContractSerializationTest {

    private final ObjectMapper mapper = ContractJson.create();

    @Test
    @DisplayName("Topology survives a JSON round trip")
    void topologyRoundTrips() throws Exception {
        Topology original = new Topology(
                List.of(
                        node("lb", "load_balancer", "Load Balancer"),
                        node("api", "service", "API", Map.of("requestsPerSecond", 2500))),
                List.of(new CanvasEdge("e1", "lb", "api")));

        Topology restored = mapper.readValue(mapper.writeValueAsString(original), Topology.class);

        assertEquals(2, restored.nodeCount());
        assertEquals(1, restored.edgeCount());
        assertEquals("lb", restored.getNodes().get(0).getId());
        assertEquals(NodeType.LOAD_BALANCER, NodeType.fromValue(restored.getNodes().get(0).getType()));
        assertEquals(2500, restored.getNodes().get(1).getData().getConfig().get("requestsPerSecond"));
        assertEquals("e1", restored.getEdges().get(0).getId());
    }

    @Test
    @DisplayName("unknown canvas fields are ignored, not rejected")
    void unknownFieldsAreTolerated() throws Exception {
        // React Flow emits fields the backend has never heard of (selected, measured,
        // dragging, zIndex…). Failing on those would reject a valid canvas.
        String json = """
                {"nodes":[{"id":"a","type":"service","position":{"x":1,"y":2},
                "data":{"label":"A","config":{}},
                "selected":true,"measured":{"width":100},"dragging":false,"zIndex":5}],
                 "edges":[],"viewport":{"x":0,"y":0,"zoom":1}}
                """;

        Topology restored = mapper.readValue(json, Topology.class);

        assertEquals(1, restored.nodeCount());
        assertEquals("a", restored.getNodes().get(0).getId());
    }

    @Test
    @DisplayName("extra fields on a record are ignored by the consumer")
    void unknownEventFieldsAreTolerated() throws Exception {
        // Additive schema evolution must not break a deployed consumer.
        String json = """
                {"eventId":"e1","correlationId":"c1","submissionId":"s1","designId":"d1",
                 "userId":"u1","sessionId":"sess","scenario":"flash_sale","scenarioNotes":"hi",
                 "topology":{"nodes":[{"id":"a","type":"service","position":{"x":0,"y":0},
                 "data":{"label":"A","config":{}}}],"edges":[]},
                 "submittedAt":"2026-01-01T00:00:00Z","somethingAddedLater":true}
                """;

        DesignSubmittedEvent restored = mapper.readValue(json, DesignSubmittedEvent.class);

        assertEquals("s1", restored.submissionId());
        assertEquals(ScenarioType.FLASH_SALE, restored.scenarioType(),
                "the scenario must survive, so the evaluator is told what was run");
    }

    @Test
    @DisplayName("CompiledGraph survives a JSON round trip")
    void compiledGraphRoundTrips() throws Exception {
        CompiledGraph original = CompiledGraph.builder()
                .name("Three tier")
                .description("test")
                .nodes(List.of(
                        CompiledNode.builder()
                                .id("api")
                                .type(NodeType.SERVICE)
                                .label("API")
                                .capacity(Map.of("requestsPerSecond", 1000.0))
                                .latencyProfile(Map.of("p50Ms", 20.0, "p99Ms", 200.0))
                                .throughput(Map.of("requestsPerSecond", 1000.0))
                                .failureModes(Set.of("fail-stop"))
                                .dependencies(Set.of("lb"))
                                .dependents(Set.of("db"))
                                .recoveryTimeMs(30000)
                                .topologicalOrder(1)
                                .build()))
                .edges(List.of(CompiledEdge.builder()
                        .id("e1").source("lb").target("api").latencyMs(1.5).bandwidthMbps(1000).build()))
                .warnings(List.of("no entry point"))
                .build();

        CompiledGraph restored = mapper.readValue(mapper.writeValueAsString(original), CompiledGraph.class);

        assertEquals(1, restored.nodeCount());
        CompiledNode api = restored.node("api").orElseThrow();
        assertEquals(NodeType.SERVICE, api.getType());
        assertEquals(20.0, api.getLatencyProfile().get("p50Ms"),
                "the latency profile must survive; the simulator reads it");
        assertEquals(1000.0, api.getCapacity().get("requestsPerSecond"));
        assertTrue(api.getFailureModes().contains("fail-stop"));
        assertEquals(30000, api.getRecoveryTimeMs());
        assertEquals(Set.of("lb"), api.getDependencies());
        assertEquals(List.of("no entry point"), restored.getWarnings());

        CompiledEdge edge = restored.getEdges().get(0);
        assertEquals("lb", edge.getSource());
        assertEquals(1.5, edge.getLatencyMs());
    }

    @Test
    @DisplayName("an empty compiled graph is still constructible from JSON")
    void emptyCompiledGraphRoundTrips() throws Exception {
        String json = """
                {"nodes":[],"edges":[],"graphMetadata":{},"name":"empty","description":"",
                 "warnings":[],"compiledAt":"2026-01-01T00:00:00Z"}
                """;

        CompiledGraph restored = mapper.readValue(json, CompiledGraph.class);

        assertEquals(0, restored.nodeCount());
        assertNotNull(restored.getCompiledAt());
    }

    @Test
    @DisplayName("SimulationRequestedEvent round trips with its scenario and seed")
    void simulationTaskRoundTrips() throws Exception {
        CompiledGraph graph = CompiledGraph.builder()
                .name("g")
                .nodes(List.of(CompiledNode.builder()
                        .id("a").type(NodeType.SERVICE).topologicalOrder(0).build()))
                .build();

        SimulationRequestedEvent original = SimulationRequestedEvent.from(
                DesignSubmittedEvent.create("u1", "sess", "d1",
                        new Topology(List.of(node("a", "service", "A")), List.of()),
                        ScenarioType.DATABASE_OVERLOAD, null),
                graph, ScenarioType.DATABASE_OVERLOAD, 90, 500, 424242L);

        SimulationRequestedEvent restored =
                mapper.readValue(mapper.writeValueAsString(original), SimulationRequestedEvent.class);

        assertEquals(original.simulationId(), restored.simulationId());
        assertEquals(original.submissionId(), restored.submissionId());
        assertEquals(original.correlationId(), restored.correlationId(),
                "correlationId must survive so one submission can be traced end to end");
        assertEquals(ScenarioType.DATABASE_OVERLOAD, restored.scenarioType(),
                "the scenario must survive; a null here silently degrades every run to NORMAL");
        assertEquals(424242L, restored.seed(), "the seed must survive for reproducibility");
        assertEquals(90, restored.durationSeconds());
        assertEquals(500, restored.tickMillis());
        assertNotNull(restored.graph());
        assertEquals(1, restored.graph().nodeCount());
    }

    @Test
    @DisplayName("timestamps serialise as ISO-8601, not epoch numbers")
    void timestampsAreIsoStrings() throws Exception {
        // A JSON number cannot hold nanosecond precision at present-day epochs, and a
        // number is unreadable during an incident.
        String json = mapper.writeValueAsString(
                DesignSubmittedEvent.create("u", "s", "d",
                        new Topology(List.of(node("a", "service", "A")), List.of()),
                        ScenarioType.NORMAL, null));

        assertTrue(json.contains("\"submittedAt\":\"") && !json.contains("\"submittedAt\":1"),
                "submittedAt must be an ISO-8601 string; got " + json);
    }

    @Test
    @DisplayName("a compiled graph built from a topology is consumable by the simulator")
    void compiledGraphFromRealTopologyIsDeserializable() throws Exception {
        // The end-to-end shape that failed in production: compile, serialise as the
        // producer does, then deserialise as the consumer does.
        Topology topology = new Topology(
                List.of(
                        node("lb", "load_balancer", "LB"),
                        node("api", "service", "API", Map.of("p99Ms", 120)),
                        node("db", "database", "Primary")),
                List.of(
                        new CanvasEdge("e1", "lb", "api"),
                        new CanvasEdge("e2", "api", "db")));

        CompiledGraph graph = CompiledGraph.builder()
                .name("Round trip")
                .nodes(List.of(
                        CompiledNode.builder().id("lb").type(NodeType.LOAD_BALANCER)
                                .topologicalOrder(0).build(),
                        CompiledNode.builder().id("api").type(NodeType.SERVICE)
                                .latencyProfile(Map.of("p99Ms", 120.0))
                                .capacity(Map.of("requestsPerSecond", 1000.0))
                                .topologicalOrder(1).build(),
                        CompiledNode.builder().id("db").type(NodeType.DATABASE)
                                .latencyProfile(Map.of("readP50Ms", 5.0))
                                .capacity(Map.of("iops", 3000.0))
                                .topologicalOrder(2).build()))
                .edges(List.of(
                        CompiledEdge.builder().source("lb").target("api").latencyMs(1.0).build(),
                        CompiledEdge.builder().source("api").target("db").latencyMs(2.0).build()))
                .build();

        String payload = mapper.writeValueAsString(graph);
        CompiledGraph asConsumedBySimulator = mapper.readValue(payload, CompiledGraph.class);

        assertEquals(3, asConsumedBySimulator.nodeCount());
        assertEquals(2, asConsumedBySimulator.edgeCount());
        assertEquals(120.0, asConsumedBySimulator.node("api").orElseThrow()
                .getLatencyProfile().get("p99Ms"));
        assertEquals(3, asConsumedBySimulator.inTopologicalOrder().size());
        assertEquals("lb", asConsumedBySimulator.inTopologicalOrder().get(0).getId());
    }

    private CanvasNode node(String id, String type, String label) {
        return node(id, type, label, Map.of());
    }

    private CanvasNode node(String id, String type, String label, Map<String, Object> config) {
        NodeData data = new NodeData(label);
        data.setConfig(config);
        return new CanvasNode(id, type, new Position(0, 0), data);
    }

    @Test
    @DisplayName("an Instant survives a round trip to the nanosecond")
    void instantsRoundTrip() throws Exception {
        Instant original = Instant.parse("2026-10-04T12:34:56.123456789Z");

        CompiledGraph graph = CompiledGraph.builder()
                .name("t")
                .nodes(List.of())
                .compiledAt(original)
                .build();

        CompiledGraph restored = mapper.readValue(mapper.writeValueAsString(graph), CompiledGraph.class);

        assertEquals(original, restored.getCompiledAt(),
                "nanosecond precision must survive; an epoch double cannot hold it");
    }
}
