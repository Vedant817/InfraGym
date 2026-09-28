package com.archforge.graphcompiler.validation;

import com.archforge.graphcompiler.schema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EdgeValidatorTest {

    private EdgeValidator validator;

    @BeforeEach
    void setUp() {
        validator = new EdgeValidator();
    }

    @Test
    void validateValidEdges() {
        ReactFlowTopology topology = createValidTopology();

        ValidationResult result = validator.validate(topology);

        assertFalse(result.hasErrors());
    }

    @Test
    void validateEdgeWithMissingSource() {
        ReactFlowTopology topology = createValidTopology();
        topology.getEdges().get(0).setSource(null);

        ValidationResult result = validator.validate(topology);

        assertTrue(result.hasErrors());
        assertTrue(result.getErrorMessages().stream().anyMatch(e -> e.contains("missing a source")));
    }

    @Test
    void validateEdgeWithMissingTarget() {
        ReactFlowTopology topology = createValidTopology();
        topology.getEdges().get(0).setTarget(null);

        ValidationResult result = validator.validate(topology);

        assertTrue(result.hasErrors());
        assertTrue(result.getErrorMessages().stream().anyMatch(e -> e.contains("missing a target")));
    }

    @Test
    void validateEdgeWithInvalidSource() {
        ReactFlowTopology topology = createValidTopology();
        topology.getEdges().get(0).setSource("non-existent");

        ValidationResult result = validator.validate(topology);

        assertTrue(result.hasErrors());
        assertTrue(result.getErrorMessages().stream().anyMatch(e -> e.contains("unknown source node")));
    }

    @Test
    void validateEdgeWithInvalidTarget() {
        ReactFlowTopology topology = createValidTopology();
        topology.getEdges().get(0).setTarget("non-existent");

        ValidationResult result = validator.validate(topology);

        assertTrue(result.hasErrors());
        assertTrue(result.getErrorMessages().stream().anyMatch(e -> e.contains("unknown target node")));
    }

    @Test
    void validateSelfLoop() {
        ReactFlowTopology topology = createValidTopology();
        topology.getEdges().get(0).setTarget(topology.getEdges().get(0).getSource());

        ValidationResult result = validator.validate(topology);

        assertTrue(result.hasErrors());
        assertTrue(result.getErrorMessages().stream().anyMatch(e -> e.contains("Self-loop")));
    }

    @Test
    void validateDuplicateEdges() {
        ReactFlowTopology topology = createValidTopology();

        ReactFlowEdge duplicate = new ReactFlowEdge();
        duplicate.setId("e3");
        duplicate.setSource("api");
        duplicate.setTarget("db");
        topology.getEdges().add(duplicate);

        ValidationResult result = validator.validate(topology);

        assertTrue(result.hasWarnings());
    }

    private ReactFlowTopology createValidTopology() {
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

        return topology;
    }
}
