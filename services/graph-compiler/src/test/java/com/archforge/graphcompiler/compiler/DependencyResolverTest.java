package com.archforge.graphcompiler.compiler;

import com.archforge.graphcompiler.schema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DependencyResolverTest {

    private GraphBuilder graphBuilder;
    private DependencyResolver dependencyResolver;

    @BeforeEach
    void setUp() {
        graphBuilder = new GraphBuilder();
        dependencyResolver = new DependencyResolver();
    }

    @Test
    void getUpstreamDependencies() {
        ReactFlowTopology topology = createValidTopology();
        InternalGraph graph = graphBuilder.build(topology);

        Set<String> upstream = dependencyResolver.getUpstreamDependencies("db", graph);

        assertTrue(upstream.contains("api"));
        assertTrue(upstream.contains("cache"));
    }

    @Test
    void getDownstreamDependencies() {
        ReactFlowTopology topology = createValidTopology();
        InternalGraph graph = graphBuilder.build(topology);

        Set<String> downstream = dependencyResolver.getDownstreamDependencies("api", graph);

        assertTrue(downstream.contains("cache"));
        assertTrue(downstream.contains("db"));
    }

    @Test
    void getAllUpstreamDependencies() {
        ReactFlowTopology topology = createValidTopology();
        InternalGraph graph = graphBuilder.build(topology);

        Map<String, Set<String>> allUpstream = dependencyResolver.getAllUpstreamDependencies(graph);

        assertEquals(3, allUpstream.size());
        assertTrue(allUpstream.get("db").contains("api"));
        assertTrue(allUpstream.get("db").contains("cache"));
    }

    @Test
    void getAllDownstreamDependencies() {
        ReactFlowTopology topology = createValidTopology();
        InternalGraph graph = graphBuilder.build(topology);

        Map<String, Set<String>> allDownstream = dependencyResolver.getAllDownstreamDependencies(graph);

        assertEquals(3, allDownstream.size());
        assertTrue(allDownstream.get("api").contains("cache"));
        assertTrue(allDownstream.get("api").contains("db"));
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
