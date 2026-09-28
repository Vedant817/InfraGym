package com.archforge.graphcompiler.validation;

import com.archforge.graphcompiler.schema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OrphanNodeDetectorTest {

    private OrphanNodeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new OrphanNodeDetector();
    }

    @Test
    void detectNoOrphans() {
        ReactFlowTopology topology = createValidTopology();

        ValidationResult result = detector.validate(topology);

        assertFalse(result.hasWarnings());
    }

    @Test
    void detectOrphanNode() {
        ReactFlowTopology topology = createValidTopology();

        ReactFlowNode orphan = new ReactFlowNode();
        orphan.setId("orphan");
        orphan.setType("service");
        orphan.setData(new NodeData());
        orphan.getData().setLabel("Orphan");
        topology.getNodes().add(orphan);

        ValidationResult result = detector.validate(topology);

        assertTrue(result.hasWarnings());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getMessage().contains("not connected")));
    }

    @Test
    void detectSingleNodeNoWarning() {
        ReactFlowTopology topology = new ReactFlowTopology();

        ReactFlowNode node = new ReactFlowNode();
        node.setId("api");
        node.setType("service");
        node.setData(new NodeData());
        node.getData().setLabel("API");

        topology.setNodes(Collections.singletonList(node));
        topology.setEdges(Collections.emptyList());

        ValidationResult result = detector.validate(topology);

        assertFalse(result.hasWarnings());
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
