package com.archforge.graphcompiler.enrichment;

import com.archforge.graphcompiler.compiler.GraphBuilder;
import com.archforge.graphcompiler.compiler.InternalGraph;
import com.archforge.graphcompiler.schema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class NodeEnricherTest {

    private SimulationParameterRegistry registry;
    private NodeEnricher nodeEnricher;
    private GraphBuilder graphBuilder;

    @BeforeEach
    void setUp() {
        registry = new SimulationParameterRegistry();
        nodeEnricher = new NodeEnricher(registry);
        graphBuilder = new GraphBuilder();
    }

    @Test
    void enrichNodesWithValidTopology() {
        ReactFlowTopology topology = createValidTopology();
        InternalGraph graph = graphBuilder.build(topology);

        Map<String, Map<String, Object>> enriched = nodeEnricher.enrichNodes(graph);

        assertEquals(3, enriched.size());
        assertTrue(enriched.containsKey("api"));
        assertTrue(enriched.containsKey("cache"));
        assertTrue(enriched.containsKey("db"));
    }

    @Test
    void enrichedNodesHaveSimulationMetadata() {
        ReactFlowTopology topology = createValidTopology();
        InternalGraph graph = graphBuilder.build(topology);

        Map<String, Map<String, Object>> enriched = nodeEnricher.enrichNodes(graph);

        Map<String, Object> apiMetadata = enriched.get("api");
        assertNotNull(apiMetadata.get("capacity"));
        assertNotNull(apiMetadata.get("latency"));
        assertNotNull(apiMetadata.get("throughput"));
        assertNotNull(apiMetadata.get("failureModes"));
    }

    @Test
    void enrichedNodesWithUserConfig() {
        ReactFlowTopology topology = createValidTopology();
        topology.getNodes().get(0).getData().setConfig(Map.of("requestsPerSecond", 5000.0));

        InternalGraph graph = graphBuilder.build(topology);
        Map<String, Map<String, Object>> enriched = nodeEnricher.enrichNodes(graph);

        Map<String, Object> apiMetadata = enriched.get("api");
        @SuppressWarnings("unchecked")
        Map<String, Double> capacity = (Map<String, Double>) apiMetadata.get("capacity");
        assertEquals(5000.0, capacity.get("requestsPerSecond"));
    }

    @Test
    void enrichNodesWithUnknownType() {
        ReactFlowTopology topology = createValidTopology();
        topology.getNodes().get(0).setType("unknown_type");

        InternalGraph graph = graphBuilder.build(topology);
        Map<String, Map<String, Object>> enriched = nodeEnricher.enrichNodes(graph);

        assertFalse(enriched.containsKey("api"));
    }

    private ReactFlowTopology createValidTopology() {
        ReactFlowTopology topology = new ReactFlowTopology();

        ReactFlowNode api = new ReactFlowNode();
        api.setId("api");
        api.setType("service");
        api.setData(new NodeData());
        api.getData().setLabel("API");

        ReactFlowNode cache = new ReactFlowNode();
        cache.setId("cache");
        cache.setType("cache");
        cache.setData(new NodeData());
        cache.getData().setLabel("Cache");

        ReactFlowNode db = new ReactFlowNode();
        db.setId("db");
        db.setType("database");
        db.setData(new NodeData());
        db.getData().setLabel("Database");

        ReactFlowEdge e1 = new ReactFlowEdge();
        e1.setId("e1");
        e1.setSource("api");
        e1.setTarget("cache");

        ReactFlowEdge e2 = new ReactFlowEdge();
        e2.setId("e2");
        e2.setSource("cache");
        e2.setTarget("db");

        topology.setNodes(Arrays.asList(api, cache, db));
        topology.setEdges(Arrays.asList(e1, e2));

        return topology;
    }
}
