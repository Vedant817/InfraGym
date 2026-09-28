package com.archforge.graphcompiler.compiler;

import com.archforge.graphcompiler.exception.CompilationException;
import com.archforge.graphcompiler.schema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GraphBuilderTest {

    private GraphBuilder graphBuilder;

    @BeforeEach
    void setUp() {
        graphBuilder = new GraphBuilder();
    }

    @Test
    void buildValidTopology() {
        ReactFlowTopology topology = createValidTopology();

        InternalGraph graph = graphBuilder.build(topology);

        assertNotNull(graph);
        assertEquals(3, graph.getNodeMap().size());
        assertEquals(2, graph.getEdges().size());
    }

    @Test
    void buildNullTopologyThrowsException() {
        assertThrows(CompilationException.class, () -> graphBuilder.build(null));
    }

    @Test
    void buildEmptyTopologyThrowsException() {
        ReactFlowTopology topology = new ReactFlowTopology();
        topology.setNodes(Collections.emptyList());
        topology.setEdges(Collections.emptyList());

        assertThrows(CompilationException.class, () -> graphBuilder.build(topology));
    }

    @Test
    void buildTopologyWithDuplicateEdges() {
        ReactFlowTopology topology = createValidTopology();

        ReactFlowEdge duplicateEdge = new ReactFlowEdge();
        duplicateEdge.setId("e3");
        duplicateEdge.setSource("api");
        duplicateEdge.setTarget("db");
        topology.getEdges().add(duplicateEdge);

        InternalGraph graph = graphBuilder.build(topology);

        assertEquals(2, graph.getEdges().size());
    }

    @Test
    void buildTopologyWithSelfLoop() {
        ReactFlowTopology topology = createValidTopology();

        ReactFlowEdge selfLoop = new ReactFlowEdge();
        selfLoop.setId("e3");
        selfLoop.setSource("api");
        selfLoop.setTarget("api");
        topology.getEdges().add(selfLoop);

        InternalGraph graph = graphBuilder.build(topology);

        assertEquals(2, graph.getEdges().size());
    }

    @Test
    void buildTopologyWithDanglingEdge() {
        ReactFlowTopology topology = createValidTopology();

        ReactFlowEdge danglingEdge = new ReactFlowEdge();
        danglingEdge.setId("e3");
        danglingEdge.setSource("api");
        danglingEdge.setTarget("non-existent");
        topology.getEdges().add(danglingEdge);

        InternalGraph graph = graphBuilder.build(topology);

        assertEquals(2, graph.getEdges().size());
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
