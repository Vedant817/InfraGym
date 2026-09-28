package com.archforge.graphcompiler.compiler;

import com.archforge.graphcompiler.exception.CompilationException;
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
    void compileEmptyTopologyThrowsException() {
        ReactFlowTopology topology = new ReactFlowTopology();
        topology.setNodes(Collections.emptyList());
        topology.setEdges(Collections.emptyList());

        assertThrows(CompilationException.class, () -> graphCompiler.compile(topology));
    }

    @Test
    void compileNullTopologyThrowsException() {
        assertThrows(CompilationException.class, () -> graphCompiler.compile(null));
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

    @Test
    void compileDisconnectedGraph() {
        ReactFlowTopology topology = new ReactFlowTopology();

        ReactFlowNode a = createNode("a", "A");
        ReactFlowNode b = createNode("b", "B");
        ReactFlowNode c = createNode("c", "C");
        ReactFlowNode d = createNode("d", "D");

        ReactFlowEdge e1 = createEdge("e1", "a", "b");
        ReactFlowEdge e2 = createEdge("e2", "c", "d");

        topology.setNodes(Arrays.asList(a, b, c, d));
        topology.setEdges(Arrays.asList(e1, e2));

        InternalGraph graph = graphCompiler.compile(topology);

        assertNotNull(graph);
        assertEquals(4, graph.getNodeMap().size());
        assertEquals(2, graph.getEdges().size());
    }

    @Test
    void compileTopologyWithIsolatedNode() {
        ReactFlowTopology topology = new ReactFlowTopology();

        ReactFlowNode a = createNode("a", "A");
        ReactFlowNode b = createNode("b", "B");
        ReactFlowNode isolated = createNode("isolated", "Isolated");

        ReactFlowEdge e1 = createEdge("e1", "a", "b");

        topology.setNodes(Arrays.asList(a, b, isolated));
        topology.setEdges(Collections.singletonList(e1));

        InternalGraph graph = graphCompiler.compile(topology);

        assertNotNull(graph);
        assertEquals(3, graph.getNodeMap().size());
        assertEquals(1, graph.getEdges().size());
    }

    private ReactFlowNode createNode(String id, String label) {
        ReactFlowNode node = new ReactFlowNode();
        node.setId(id);
        node.setType("service");
        node.setData(new NodeData());
        node.getData().setLabel(label);
        return node;
    }

    private ReactFlowEdge createEdge(String id, String source, String target) {
        ReactFlowEdge edge = new ReactFlowEdge();
        edge.setId(id);
        edge.setSource(source);
        edge.setTarget(target);
        return edge;
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
