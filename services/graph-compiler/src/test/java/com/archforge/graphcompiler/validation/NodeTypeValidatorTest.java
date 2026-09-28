package com.archforge.graphcompiler.validation;

import com.archforge.graphcompiler.schema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class NodeTypeValidatorTest {

    private NodeTypeValidator validator;

    @BeforeEach
    void setUp() {
        validator = new NodeTypeValidator();
    }

    @Test
    void validateValidNodeTypes() {
        ReactFlowTopology topology = createValidTopology();

        ValidationResult result = validator.validate(topology);

        assertFalse(result.hasErrors());
    }

    @Test
    void validateInvalidNodeType() {
        ReactFlowTopology topology = createValidTopology();
        topology.getNodes().get(0).setType("invalid_type");

        ValidationResult result = validator.validate(topology);

        assertTrue(result.hasErrors());
        assertTrue(result.getErrorMessages().stream().anyMatch(e -> e.contains("unknown type")));
    }

    @Test
    void validateNodeTypeWithSuggestion() {
        ReactFlowTopology topology = createValidTopology();
        topology.getNodes().get(0).setType("databse");

        ValidationResult result = validator.validate(topology);

        assertTrue(result.hasErrors());
        assertTrue(result.getErrorMessages().stream().anyMatch(e -> e.contains("Did you mean")));
    }

    @Test
    void validateNullNodeType() {
        ReactFlowTopology topology = createValidTopology();
        topology.getNodes().get(0).setType(null);

        ValidationResult result = validator.validate(topology);

        assertFalse(result.hasErrors());
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
