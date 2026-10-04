package com.archforge.graphcompiler.compiler;

import com.archforge.graphcompiler.exception.DAGCycleException;
import com.archforge.graphcompiler.support.TestTopologies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TopologicalSorter}.
 *
 * <p>Two properties are asserted that the previous suite never checked:
 * <ul>
 *   <li><b>Determinism.</b> The same graph must produce the same order on every run.
 *       With a plain {@code ArrayDeque} of ready nodes the result depended on
 *       {@code HashMap} iteration order, so the identical design could compile to
 *       different DAGs on different JVMs — and the AI evaluator's bottleneck would move
 *       between runs for no reason.</li>
 *   <li><b>Cycle reporting.</b> A cycle must be rejected with a
 *       {@link DAGCycleException} naming an actual path, not merely reported as "produced
 *       N of M nodes".</li>
 * </ul>
 */
class TopologicalSorterTest {

    private final GraphBuilder graphBuilder = new GraphBuilder();
    private final TopologicalSorter sorter = new TopologicalSorter();

    @Test
    @DisplayName("orders nodes so every dependency precedes its dependent")
    void ordersDependenciesFirst() {
        InternalGraph graph = graphBuilder.build(TestTopologies.validThreeTier());

        List<String> order = sorter.sort(graph);

        assertEquals(4, order.size(), "every node must appear in the order");
        assertTrue(order.indexOf("lb") < order.indexOf("api"),
                "the load balancer feeds the API, so it must come first");
        assertTrue(order.indexOf("api") < order.indexOf("cache"),
                "the API feeds the cache, so it must come first");
        assertTrue(order.indexOf("api") < order.indexOf("db"),
                "the API feeds the database, so it must come first");
    }

    @Test
    @DisplayName("is deterministic across repeated runs")
    void isDeterministic() {
        InternalGraph graph = graphBuilder.build(TestTopologies.validThreeTier());
        List<String> first = sorter.sort(graph);

        for (int run = 0; run < 50; run++) {
            assertEquals(first, sorter.sort(graph),
                    "run " + run + " produced a different order; the sort is not deterministic");
        }
    }

    @Test
    @DisplayName("rejects a cycle and names a path through it")
    void rejectsCycleWithPath() {
        InternalGraph graph = graphBuilder.build(TestTopologies.withCycle());

        DAGCycleException ex = assertThrows(DAGCycleException.class, () -> sorter.sort(graph));

        assertTrue(ex.getCyclePath().size() >= 2,
                "the reported cycle must contain at least two nodes, got " + ex.getCyclePath());
        assertTrue(ex.getMessage().toLowerCase().contains("cycle"),
                "the message must name the problem, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("rejects a longer cycle")
    void rejectsLongerCycle() {
        InternalGraph graph = graphBuilder.build(new com.archforge.contracts.canvas.Topology(
                java.util.List.of(
                        TestTopologies.node("a", "service", "A"),
                        TestTopologies.node("b", "service", "B"),
                        TestTopologies.node("c", "service", "C")),
                java.util.List.of(
                        TestTopologies.edge("e1", "a", "b"),
                        TestTopologies.edge("e2", "b", "c"),
                        TestTopologies.edge("e3", "c", "a"))));

        DAGCycleException ex = assertThrows(DAGCycleException.class, () -> sorter.sort(graph));

        Set<String> inPath = new HashSet<>(ex.getCyclePath());
        assertTrue(inPath.contains("a") || inPath.contains("b") || inPath.contains("c"),
                "the cycle path must name real nodes, got " + ex.getCyclePath());
    }

    @Test
    @DisplayName("handles a graph with no edges")
    void handlesEdgelessGraph() {
        InternalGraph graph = graphBuilder.build(new com.archforge.contracts.canvas.Topology(
                java.util.List.of(
                        TestTopologies.node("a", "service", "A"),
                        TestTopologies.node("b", "service", "B")),
                java.util.List.of()));

        assertEquals(Set.of("a", "b"), new HashSet<>(sorter.sort(graph)));
    }
}
