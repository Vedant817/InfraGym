package com.archforge.graphcompiler.enrichment;

import com.archforge.graphcompiler.compiler.GraphBuilder;
import com.archforge.graphcompiler.compiler.GraphMetricsCalculator;
import com.archforge.graphcompiler.compiler.InternalGraph;
import com.archforge.graphcompiler.compiler.TopologicalSorter;
import com.archforge.graphcompiler.schema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GraphMetadataGeneratorTest {

    private GraphMetadataGenerator generator;
    private GraphBuilder graphBuilder;
    private TopologicalSorter sorter;
    private GraphMetricsCalculator metricsCalculator;

    @BeforeEach
    void setUp() {
        metricsCalculator = new GraphMetricsCalculator();
        generator = new GraphMetadataGenerator(metricsCalculator);
        graphBuilder = new GraphBuilder();
        sorter = new TopologicalSorter();
    }

    @Test
    void generateMetadataForValidTopology() {
        ReactFlowTopology topology = createValidTopology();
        InternalGraph graph = graphBuilder.build(topology);
        graph.setTopologicalOrder(sorter.sort(graph));
        graph.setNodeMetrics(metricsCalculator.calculate(graph));

        Map<String, Object> metadata = generator.generateMetadata(graph);

        assertEquals(3, metadata.get("totalNodes"));
        assertEquals(2, metadata.get("totalEdges"));
        assertNotNull(metadata.get("graphDepth"));
        assertNotNull(metadata.get("graphBreadth"));
        assertNotNull(metadata.get("complexityScore"));
    }

    @Test
    void metadataContainsNodeTypeCounts() {
        ReactFlowTopology topology = createValidTopology();
        InternalGraph graph = graphBuilder.build(topology);
        graph.setTopologicalOrder(sorter.sort(graph));
        graph.setNodeMetrics(metricsCalculator.calculate(graph));

        Map<String, Object> metadata = generator.generateMetadata(graph);

        @SuppressWarnings("unchecked")
        Map<String, Integer> typeCounts = (Map<String, Integer>) metadata.get("nodeTypeCounts");
        assertTrue(typeCounts.containsKey("service"));
        assertTrue(typeCounts.containsKey("cache"));
        assertTrue(typeCounts.containsKey("database"));
    }

    @Test
    void metadataContainsComplexityRating() {
        ReactFlowTopology topology = createValidTopology();
        InternalGraph graph = graphBuilder.build(topology);
        graph.setTopologicalOrder(sorter.sort(graph));
        graph.setNodeMetrics(metricsCalculator.calculate(graph));

        Map<String, Object> metadata = generator.generateMetadata(graph);

        String rating = (String) metadata.get("complexityRating");
        assertTrue(List.of("LOW", "MEDIUM", "HIGH", "VERY_HIGH").contains(rating));
    }

    @Test
    void metadataForSingleNode() {
        ReactFlowTopology topology = new ReactFlowTopology();

        ReactFlowNode node = new ReactFlowNode();
        node.setId("api");
        node.setType("service");
        node.setData(new NodeData());
        node.getData().setLabel("API");

        topology.setNodes(Collections.singletonList(node));
        topology.setEdges(Collections.emptyList());

        InternalGraph graph = graphBuilder.build(topology);
        graph.setTopologicalOrder(sorter.sort(graph));
        graph.setNodeMetrics(metricsCalculator.calculate(graph));

        Map<String, Object> metadata = generator.generateMetadata(graph);

        assertEquals(1, metadata.get("totalNodes"));
        assertEquals(0, metadata.get("totalEdges"));
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
