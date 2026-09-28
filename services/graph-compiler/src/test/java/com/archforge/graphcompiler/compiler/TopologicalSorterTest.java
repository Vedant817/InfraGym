package com.archforge.graphcompiler.compiler;

import com.archforge.graphcompiler.exception.CompilationException;
import com.archforge.graphcompiler.schema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TopologicalSorterTest {

    private GraphBuilder graphBuilder;
    private TopologicalSorter topologicalSorter;

    @BeforeEach
    void setUp() {
        graphBuilder = new GraphBuilder();
        topologicalSorter = new TopologicalSorter();
    }

    @Test
    void sortValidDAG() {
        ReactFlowTopology topology = createValidTopology();
        InternalGraph graph = graphBuilder.build(topology);

        List<String> sorted = topologicalSorter.sort(graph);

        assertEquals(3, sorted.size());
        assertTrue(sorted.indexOf("api") < sorted.indexOf("cache"));
        assertTrue(sorted.indexOf("cache") < sorted.indexOf("db"));
    }

    @Test
    void sortCyclicGraphThrowsException() {
        ReactFlowTopology topology = createCyclicTopology();
        InternalGraph graph = graphBuilder.build(topology);

        assertThrows(CompilationException.class, () -> topologicalSorter.sort(graph));
    }

    @Test
    void sortDisconnectedGraph() {
        ReactFlowTopology topology = createDisconnectedTopology();
        InternalGraph graph = graphBuilder.build(topology);

        List<String> sorted = topologicalSorter.sort(graph);

        assertEquals(4, sorted.size());
    }

    @Test
    void sortSingleNode() {
        ReactFlowTopology topology = new ReactFlowTopology();

        ReactFlowNode node = new ReactFlowNode();
        node.setId("api");
        node.setType("service");
        node.setData(new NodeData());
        node.getData().setLabel("API");

        topology.setNodes(Collections.singletonList(node));
        topology.setEdges(Collections.emptyList());

        InternalGraph graph = graphBuilder.build(topology);
        List<String> sorted = topologicalSorter.sort(graph);

        assertEquals(1, sorted.size());
        assertEquals("api", sorted.get(0));
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

    private ReactFlowTopology createCyclicTopology() {
        ReactFlowTopology topology = new ReactFlowTopology();

        ReactFlowNode a = new ReactFlowNode();
        a.setId("a");
        a.setType("service");
        a.setData(new NodeData());
        a.getData().setLabel("A");

        ReactFlowNode b = new ReactFlowNode();
        b.setId("b");
        b.setType("service");
        b.setData(new NodeData());
        b.getData().setLabel("B");

        ReactFlowNode c = new ReactFlowNode();
        c.setId("c");
        c.setType("service");
        c.setData(new NodeData());
        c.getData().setLabel("C");

        ReactFlowEdge e1 = new ReactFlowEdge();
        e1.setId("e1");
        e1.setSource("a");
        e1.setTarget("b");

        ReactFlowEdge e2 = new ReactFlowEdge();
        e2.setId("e2");
        e2.setSource("b");
        e2.setTarget("c");

        ReactFlowEdge e3 = new ReactFlowEdge();
        e3.setId("e3");
        e3.setSource("c");
        e3.setTarget("a");

        topology.setNodes(Arrays.asList(a, b, c));
        topology.setEdges(Arrays.asList(e1, e2, e3));

        return topology;
    }

    private ReactFlowTopology createDisconnectedTopology() {
        ReactFlowTopology topology = new ReactFlowTopology();

        ReactFlowNode a = new ReactFlowNode();
        a.setId("a");
        a.setType("service");
        a.setData(new NodeData());
        a.getData().setLabel("A");

        ReactFlowNode b = new ReactFlowNode();
        b.setId("b");
        b.setType("service");
        b.setData(new NodeData());
        b.getData().setLabel("B");

        ReactFlowNode c = new ReactFlowNode();
        c.setId("c");
        c.setType("service");
        c.setData(new NodeData());
        c.getData().setLabel("C");

        ReactFlowNode d = new ReactFlowNode();
        d.setId("d");
        d.setType("service");
        d.setData(new NodeData());
        d.getData().setLabel("D");

        ReactFlowEdge e1 = new ReactFlowEdge();
        e1.setId("e1");
        e1.setSource("a");
        e1.setTarget("b");

        ReactFlowEdge e2 = new ReactFlowEdge();
        e2.setId("e2");
        e2.setSource("c");
        e2.setTarget("d");

        topology.setNodes(Arrays.asList(a, b, c, d));
        topology.setEdges(Arrays.asList(e1, e2));

        return topology;
    }
}
