package com.archforge.graphcompiler.compiler;

import com.archforge.graphcompiler.exception.CompilationException;
import com.archforge.graphcompiler.support.TestTopologies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GraphBuilder}.
 *
 * <p>The regression that matters most here: a topology whose nodes all lack ids used to
 * build a <em>zero-node graph</em> and carry on. That graph was compiled, published to
 * {@code simulation.tasks} as a legitimate task, and consumed by the simulator as an
 * architecture with no components. It now fails.
 */
class GraphBuilderTest {

    private final GraphBuilder builder = new GraphBuilder();

    @Test
    @DisplayName("builds adjacency in both directions")
    void buildsBidirectionalAdjacency() {
        InternalGraph graph = builder.build(TestTopologies.validThreeTier());

        assertEquals(4, graph.nodeCount());
        assertEquals(3, graph.edgeCount());
        assertTrue(graph.getAdjacencyList().get("lb").contains("api"));
        assertTrue(graph.getReverseAdjacencyList().get("api").contains("lb"));
    }

    @Test
    @DisplayName("collapses duplicate edges to one")
    void collapsesDuplicateEdges() {
        InternalGraph graph = builder.build(TestTopologies.withDuplicateEdges());

        // a->b appears twice in the fixture; only one may survive.
        long aToB = graph.getAdjacencyList().getOrDefault("a", java.util.List.of())
                .stream().filter(target -> target.equals("b")).count();
        assertEquals(1, aToB, "a duplicate edge must be collapsed, not double-counted");
    }

    @Test
    @DisplayName("rejects a self-loop and records why")
    void rejectsSelfLoop() {
        InternalGraph graph = builder.build(TestTopologies.withSelfLoop());

        assertEquals(1, graph.edgeCount(), "the self-loop must not be compiled in");
        assertTrue(graph.rejectedEdgesOrEmpty().stream()
                        .anyMatch(rejected -> rejected.reason().equals("SELF_LOOP")),
                "the self-loop must be recorded as rejected, got " + graph.rejectedEdgesOrEmpty());
    }

    @Test
    @DisplayName("drops an edge referencing a missing node and records why")
    void dropsDanglingEdge() {
        InternalGraph graph = builder.build(TestTopologies.withDanglingEdge());

        assertEquals(1, graph.edgeCount());
        assertTrue(graph.rejectedEdgesOrEmpty().stream()
                        .anyMatch(rejected -> rejected.reason().equals("UNKNOWN_NODE")),
                "the dangling edge must be recorded, got " + graph.rejectedEdgesOrEmpty());
    }

    @Test
    @DisplayName("fails when no node has a usable id")
    void failsWhenNoNodeHasAnId() {
        // Regression: this used to produce a zero-node graph that flowed on to the
        // simulator as a valid, empty architecture.
        CompilationException ex = assertThrows(CompilationException.class,
                () -> builder.build(TestTopologies.allNodesWithoutIds()));

        assertTrue(ex.getMessage().contains("usable id"),
                "the message must explain the problem, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("rejects a null topology")
    void rejectsNullTopology() {
        assertThrows(CompilationException.class, () -> builder.build(null));
    }

    @Test
    @DisplayName("rejects an empty topology")
    void rejectsEmptyTopology() {
        assertThrows(CompilationException.class,
                () -> builder.build(com.archforge.contracts.canvas.Topology.empty()));
    }

    @Test
    @DisplayName("exposes unmodifiable collections")
    void exposesUnmodifiableCollections() {
        InternalGraph graph = builder.build(TestTopologies.validThreeTier());

        assertNotNull(graph.getNodeMap());
        assertThrows(UnsupportedOperationException.class,
                () -> graph.getNodeMap().put("x", null));
        assertThrows(UnsupportedOperationException.class,
                () -> graph.getEdges().add(null));
    }
}
