package com.archforge.graphcompiler.validation;

import com.archforge.graphcompiler.schema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CycleDetectorTest {

    private CycleDetector detector;

    @BeforeEach
    void setUp() {
        detector = new CycleDetector();
    }

    @Test
    void detectNoCycles() {
        ReactFlowTopology topology = createAcyclicTopology();

        List<List<String>> cycles = detector.detectCycles(topology);

        assertTrue(cycles.isEmpty());
    }

    @Test
    void detectSimpleCycle() {
        ReactFlowTopology topology = createCyclicTopology();

        List<List<String>> cycles = detector.detectCycles(topology);

        assertFalse(cycles.isEmpty());
        assertTrue(cycles.get(0).contains("a"));
        assertTrue(cycles.get(0).contains("b"));
        assertTrue(cycles.get(0).contains("c"));
    }

    @Test
    void detectSelfLoop() {
        ReactFlowTopology topology = new ReactFlowTopology();

        ReactFlowNode node = new ReactFlowNode();
        node.setId("a");
        node.setType("service");
        node.setData(new NodeData());
        node.getData().setLabel("Node A");

        ReactFlowEdge edge = new ReactFlowEdge();
        edge.setId("e1");
        edge.setSource("a");
        edge.setTarget("a");

        topology.setNodes(Collections.singletonList(node));
        topology.setEdges(Collections.singletonList(edge));

        List<List<String>> cycles = detector.detectCycles(topology);

        assertFalse(cycles.isEmpty());
    }

    @Test
    void detectNullTopology() {
        List<List<String>> cycles = detector.detectCycles(null);
        assertTrue(cycles.isEmpty());
    }

    private ReactFlowTopology createAcyclicTopology() {
        ReactFlowTopology topology = new ReactFlowTopology();

        ReactFlowNode a = createNode("a", "A");
        ReactFlowNode b = createNode("b", "B");
        ReactFlowNode c = createNode("c", "C");

        ReactFlowEdge e1 = createEdge("e1", "a", "b");
        ReactFlowEdge e2 = createEdge("e2", "b", "c");

        topology.setNodes(Arrays.asList(a, b, c));
        topology.setEdges(Arrays.asList(e1, e2));

        return topology;
    }

    private ReactFlowTopology createCyclicTopology() {
        ReactFlowTopology topology = new ReactFlowTopology();

        ReactFlowNode a = createNode("a", "A");
        ReactFlowNode b = createNode("b", "B");
        ReactFlowNode c = createNode("c", "C");

        ReactFlowEdge e1 = createEdge("e1", "a", "b");
        ReactFlowEdge e2 = createEdge("e2", "b", "c");
        ReactFlowEdge e3 = createEdge("e3", "c", "a");

        topology.setNodes(Arrays.asList(a, b, c));
        topology.setEdges(Arrays.asList(e1, e2, e3));

        return topology;
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
}
