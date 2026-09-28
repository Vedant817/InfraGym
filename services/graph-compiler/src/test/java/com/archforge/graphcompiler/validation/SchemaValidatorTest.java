package com.archforge.graphcompiler.validation;

import com.archforge.graphcompiler.schema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SchemaValidatorTest {

    private SchemaValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SchemaValidator();
    }

    @Test
    void validateNullTopology() {
        ValidationResult result = validator.validate(null);
        assertTrue(result.hasErrors());
        assertEquals(1, result.getErrors().size());
    }

    @Test
    void validateEmptyTopology() {
        ReactFlowTopology topology = new ReactFlowTopology();
        topology.setNodes(Collections.emptyList());
        topology.setEdges(Collections.emptyList());

        ValidationResult result = validator.validate(topology);
        assertTrue(result.hasErrors());
    }

    @Test
    void validateValidTopology() {
        ReactFlowTopology topology = createValidTopology();

        ValidationResult result = validator.validate(topology);
        assertFalse(result.hasErrors());
    }

    @Test
    void validateDuplicateNodeIds() {
        ReactFlowTopology topology = createValidTopology();
        ReactFlowNode duplicateNode = new ReactFlowNode();
        duplicateNode.setId("api");
        duplicateNode.setType("service");
        duplicateNode.setData(new NodeData());
        duplicateNode.getData().setLabel("Duplicate API");
        topology.getNodes().add(duplicateNode);

        ValidationResult result = validator.validate(topology);
        assertTrue(result.hasErrors());
        assertTrue(result.getErrorMessages().stream().anyMatch(e -> e.contains("Duplicate node ID")));
    }

    @Test
    void validateMissingNodeLabel() {
        ReactFlowTopology topology = createValidTopology();
        topology.getNodes().get(0).getData().setLabel(null);

        ValidationResult result = validator.validate(topology);
        assertTrue(result.hasWarnings());
    }

    @Test
    void validateTooManyNodes() {
        ReactFlowTopology topology = new ReactFlowTopology();
        List<ReactFlowNode> nodes = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            ReactFlowNode node = new ReactFlowNode();
            node.setId("node-" + i);
            node.setType("service");
            node.setData(new NodeData());
            node.getData().setLabel("Node " + i);
            nodes.add(node);
        }
        topology.setNodes(nodes);
        topology.setEdges(Collections.emptyList());

        ValidationResult result = validator.validate(topology);
        assertTrue(result.hasErrors());
        assertTrue(result.getErrorMessages().stream().anyMatch(e -> e.contains("exceeds maximum")));
    }

    private ReactFlowTopology createValidTopology() {
        ReactFlowTopology topology = new ReactFlowTopology();

        ReactFlowNode apiNode = new ReactFlowNode();
        apiNode.setId("api");
        apiNode.setType("service");
        apiNode.setData(new NodeData());
        apiNode.getData().setLabel("API Service");

        ReactFlowNode dbNode = new ReactFlowNode();
        dbNode.setId("db");
        dbNode.setType("database");
        dbNode.setData(new NodeData());
        dbNode.getData().setLabel("Database");

        ReactFlowEdge edge = new ReactFlowEdge();
        edge.setId("e1");
        edge.setSource("api");
        edge.setTarget("db");

        topology.setNodes(Arrays.asList(apiNode, dbNode));
        topology.setEdges(Collections.singletonList(edge));

        return topology;
    }
}
