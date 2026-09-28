package com.archforge.graphcompiler.validation;

import com.archforge.graphcompiler.schema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SemanticValidatorTest {

    private SemanticValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SemanticValidator();
    }

    @Test
    void validateTopologyWithEntryPoint() {
        ReactFlowTopology topology = createValidTopology();

        ValidationResult result = validator.validate(topology);

        assertFalse(result.hasWarnings());
    }

    @Test
    void validateTopologyWithoutEntryPoint() {
        ReactFlowTopology topology = new ReactFlowTopology();

        ReactFlowNode api = new ReactFlowNode();
        api.setId("api");
        api.setType("service");
        api.setData(new NodeData());
        api.getData().setLabel("API");

        ReactFlowNode db = new ReactFlowNode();
        db.setId("db");
        db.setType("database");
        db.setData(new NodeData());
        db.getData().setLabel("Database");

        ReactFlowEdge edge = new ReactFlowEdge();
        edge.setId("e1");
        edge.setSource("api");
        edge.setTarget("db");

        topology.setNodes(Arrays.asList(api, db));
        topology.setEdges(Collections.singletonList(edge));

        ValidationResult result = validator.validate(topology);

        assertTrue(result.hasWarnings());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getMessage().contains("no entry point")));
    }

    @Test
    void validateTopologyWithoutComputeNode() {
        ReactFlowTopology topology = new ReactFlowTopology();

        ReactFlowNode lb = new ReactFlowNode();
        lb.setId("lb");
        lb.setType("load_balancer");
        lb.setData(new NodeData());
        lb.getData().setLabel("Load Balancer");

        ReactFlowNode db = new ReactFlowNode();
        db.setId("db");
        db.setType("database");
        db.setData(new NodeData());
        db.getData().setLabel("Database");

        ReactFlowEdge edge = new ReactFlowEdge();
        edge.setId("e1");
        edge.setSource("lb");
        edge.setTarget("db");

        topology.setNodes(Arrays.asList(lb, db));
        topology.setEdges(Collections.singletonList(edge));

        ValidationResult result = validator.validate(topology);

        assertTrue(result.hasWarnings());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getMessage().contains("no compute node")));
    }

    private ReactFlowTopology createValidTopology() {
        ReactFlowTopology topology = new ReactFlowTopology();

        ReactFlowNode lb = new ReactFlowNode();
        lb.setId("lb");
        lb.setType("load_balancer");
        lb.setData(new NodeData());
        lb.getData().setLabel("Load Balancer");

        ReactFlowNode api = new ReactFlowNode();
        api.setId("api");
        api.setType("service");
        api.setData(new NodeData());
        api.getData().setLabel("API");

        ReactFlowNode db = new ReactFlowNode();
        db.setId("db");
        db.setType("database");
        db.setData(new NodeData());
        db.getData().setLabel("Database");

        ReactFlowEdge e1 = new ReactFlowEdge();
        e1.setId("e1");
        e1.setSource("lb");
        e1.setTarget("api");

        ReactFlowEdge e2 = new ReactFlowEdge();
        e2.setId("e2");
        e2.setSource("api");
        e2.setTarget("db");

        topology.setNodes(Arrays.asList(lb, api, db));
        topology.setEdges(Arrays.asList(e1, e2));

        return topology;
    }
}
