package com.archforge.graphcompiler.compiler;

import com.archforge.graphcompiler.enrichment.GraphEnrichmentPipeline;
import com.archforge.graphcompiler.enrichment.GraphMetadataGenerator;
import com.archforge.graphcompiler.enrichment.NodeEnricher;
import com.archforge.graphcompiler.enrichment.SimulationParameterRegistry;
import com.archforge.graphcompiler.model.CompiledGraph;
import com.archforge.graphcompiler.model.CompiledNode;
import com.archforge.graphcompiler.schema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DagCompilerTest {

    private DagCompiler dagCompiler;

    @BeforeEach
    void setUp() {
        SimulationParameterRegistry registry = new SimulationParameterRegistry();
        NodeEnricher nodeEnricher = new NodeEnricher(registry);
        GraphMetadataGenerator metadataGenerator = new GraphMetricsCalculator();
        GraphEnrichmentPipeline pipeline = new GraphEnrichmentPipeline(nodeEnricher, metadataGenerator);
        dagCompiler = new DagCompiler(pipeline);
    }

    @Test
    void compileValidGraph() {
        ReactFlowTopology topology = createValidTopology();
        GraphBuilder graphBuilder = new GraphBuilder();
        TopologicalSorter sorter = new TopologicalSorter();
        GraphMetricsCalculator metricsCalculator = new GraphMetricsCalculator();

        InternalGraph internalGraph = graphBuilder.build(topology);
        internalGraph.setTopologicalOrder(sorter.sort(internalGraph));
        internalGraph.setNodeMetrics(metricsCalculator.calculate(internalGraph));

        CompiledGraph compiledGraph = dagCompiler.compile(internalGraph);

        assertNotNull(compiledGraph);
        assertEquals(3, compiledGraph.getNodes().size());
        assertEquals(2, compiledGraph.getEdges().size());
        assertNotNull(compiledGraph.getGraphMetadata());
    }

    @Test
    void compiledNodesHaveCorrectTopologicalOrder() {
        ReactFlowTopology topology = createValidTopology();
        GraphBuilder graphBuilder = new GraphBuilder();
        TopologicalSorter sorter = new TopologicalSorter();
        GraphMetricsCalculator metricsCalculator = new GraphMetricsCalculator();

        InternalGraph internalGraph = graphBuilder.build(topology);
        internalGraph.setTopologicalOrder(sorter.sort(internalGraph));
        internalGraph.setNodeMetrics(metricsCalculator.calculate(internalGraph));

        CompiledGraph compiledGraph = dagCompiler.compile(internalGraph);

        for (CompiledNode node : compiledGraph.getNodes()) {
            assertTrue(node.getTopologicalOrder() >= 0);
            assertTrue(node.getTopologicalOrder() < 3);
        }
    }

    @Test
    void compiledNodesHaveSimulationMetadata() {
        ReactFlowTopology topology = createValidTopology();
        GraphBuilder graphBuilder = new GraphBuilder();
        TopologicalSorter sorter = new TopologicalSorter();
        GraphMetricsCalculator metricsCalculator = new GraphMetricsCalculator();

        InternalGraph internalGraph = graphBuilder.build(topology);
        internalGraph.setTopologicalOrder(sorter.sort(internalGraph));
        internalGraph.setNodeMetrics(metricsCalculator.calculate(internalGraph));

        CompiledGraph compiledGraph = dagCompiler.compile(internalGraph);

        for (CompiledNode node : compiledGraph.getNodes()) {
            assertNotNull(node.getSimulationMetadata());
            assertFalse(node.getSimulationMetadata().isEmpty());
        }
    }

    @Test
    void compileNullGraphThrowsException() {
        assertThrows(NullPointerException.class, () -> dagCompiler.compile(null));
    }

    @Test
    void compileGraphWithNullTopologicalOrderThrowsException() {
        ReactFlowTopology topology = createValidTopology();
        GraphBuilder graphBuilder = new GraphBuilder();

        InternalGraph internalGraph = graphBuilder.build(topology);
        internalGraph.setTopologicalOrder(null);

        assertThrows(NullPointerException.class, () -> dagCompiler.compile(internalGraph));
    }

    @Test
    void compiledEdgesHaveProtocolInference() {
        ReactFlowTopology topology = createValidTopology();
        GraphBuilder graphBuilder = new GraphBuilder();
        TopologicalSorter sorter = new TopologicalSorter();
        GraphMetricsCalculator metricsCalculator = new GraphMetricsCalculator();

        InternalGraph internalGraph = graphBuilder.build(topology);
        internalGraph.setTopologicalOrder(sorter.sort(internalGraph));
        internalGraph.setNodeMetrics(metricsCalculator.calculate(internalGraph));

        CompiledGraph compiledGraph = dagCompiler.compile(internalGraph);

        assertFalse(compiledGraph.getEdges().isEmpty());
        for (var edge : compiledGraph.getEdges()) {
            assertNotNull(edge.getProtocol());
            assertNotEquals("unknown", edge.getProtocol());
        }
    }

    @Test
    void compiledEdgesHaveBandwidthAndLatency() {
        ReactFlowTopology topology = createValidTopology();
        GraphBuilder graphBuilder = new GraphBuilder();
        TopologicalSorter sorter = new TopologicalSorter();
        GraphMetricsCalculator metricsCalculator = new GraphMetricsCalculator();

        InternalGraph internalGraph = graphBuilder.build(topology);
        internalGraph.setTopologicalOrder(sorter.sort(internalGraph));
        internalGraph.setNodeMetrics(metricsCalculator.calculate(internalGraph));

        CompiledGraph compiledGraph = dagCompiler.compile(internalGraph);

        for (var edge : compiledGraph.getEdges()) {
            assertNotNull(edge.getBandwidthMbps());
            assertNotNull(edge.getLatencyMs());
            assertTrue(edge.getBandwidthMbps() > 0);
            assertTrue(edge.getLatencyMs() > 0);
        }
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
