package com.archforge.graphcompiler.compiler;

import com.archforge.graphcompiler.schema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GraphCompilerTest {

    private GraphBuilder graphBuilder;
    private TopologicalSorter topologicalSorter;
    private GraphMetricsCalculator metricsCalculator;
    private DependencyResolver dependencyResolver;
    private GraphCompiler graphCompiler;

    @BeforeEach
    void setUp() {
        graphBuilder = new GraphBuilder();
        topologicalSorter = new TopologicalSorter();
        metricsCalculator = new GraphMetricsCalculator();
        dependencyResolver = new DependencyResolver();
        graphCompiler = new GraphCompiler(graphBuilder, topologicalSorter, metricsCalculator, dependencyResolver);
    }

    @Test
    void compileValidTopology() {
        ReactFlowTopology topology = createValidTopology();

        InternalGraph graph = graphCompiler.compile(topology);

        assertNotNull(graph);
        assertEquals(3, graph.getNodeMap().size());
        assertEquals(2, graph.getEdges().size());
        assertNotNull(graph.getTopologicalOrder());
        assertEquals(3, graph.getTopologicalOrder().size());
    }

    @Test
    void compileEmptyTopology() {
        ReactFlowTopology topology = new ReactFlowTopology();
        topology.setNodes(Collections.emptyList());
        topology.setEdges(Collections.emptyList());

        assertThrows(Exception.class, () -> graphCompiler.compile(topology));
    }

    @Test
    void compileSingleNodeTopology() {
        ReactFlowTopology topology = new ReactFlowTopology();

        ReactFlowNode node = new ReactFlowNode();
        node.setId("api");
        node.setType("service");
        node.setData(new NodeData());
        node.getData().setLabel("API");

        topology.setNodes(Collections.singletonList(node));
        topology.setEdges(Collections.emptyList());

        InternalGraph graph = graphCompiler.compile(topology);

        assertNotNull(graph);
        assertEquals(1, graph.getNodeMap().size());
        assertEquals(0, graph.getEdges().size());
    }

    @Test
    void topologicalOrderIsCorrect() {
        ReactFlowTopology topology = createValidTopology();

        InternalGraph graph = graphCompiler.compile(topology);

        List<String> topoOrder = graph.getTopologicalOrder();
        assertTrue(topoOrder.indexOf("api") < topoOrder.indexOf("cache"));
        assertTrue(topoOrder.indexOf("cache") < topoOrder.indexOf("db"));
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
