package com.archforge.graphcompiler.validation;

import com.archforge.contracts.canvas.CanvasEdge;
import com.archforge.contracts.canvas.CanvasNode;
import com.archforge.contracts.canvas.NodeData;
import com.archforge.contracts.canvas.Topology;
import com.archforge.graphcompiler.support.TestTopologies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CycleDetector}.
 *
 * <p>Regression coverage for the duplicate reports: the previous implementation emitted a
 * cycle for every back-edge it met, so a three-node complete graph produced four cycles
 * for the same underlying defect, two of them identical — and each became its own
 * {@code CYCLE_DETECTED} error the user had to read and de-duplicate.
 */
class CycleDetectorTest {

    private final CycleDetector detector = new CycleDetector();

    @Test
    @DisplayName("finds a two-node cycle")
    void findsTwoNodeCycle() {
        List<List<String>> cycles = detector.detectCycles(TestTopologies.withCycle());

        assertEquals(1, cycles.size(), "a simple a->b->a must produce exactly one cycle, got " + cycles);
    }

    @Test
    @DisplayName("reports no cycles for a DAG")
    void reportsNothingForADag() {
        assertTrue(detector.detectCycles(TestTopologies.validThreeTier()).isEmpty(),
                "a valid three-tier design must produce no cycles");
    }

    @Test
    @DisplayName("finds a three-node cycle")
    void findsThreeNodeCycle() {
        Topology topology = new Topology(
                List.of(
                        TestTopologies.node("a", "service", "A"),
                        TestTopologies.node("b", "service", "B"),
                        TestTopologies.node("c", "service", "C")),
                List.of(
                        TestTopologies.edge("e1", "a", "b"),
                        TestTopologies.edge("e2", "b", "c"),
                        TestTopologies.edge("e3", "c", "a")));

        List<List<String>> cycles = detector.detectCycles(topology);

        assertEquals(1, cycles.size(), "got " + cycles);
    }

    @Test
    @DisplayName("never reports the same cycle twice")
    void neverReportsTheSameCycleTwice() {
        // A complete graph on three nodes has many back-edges. The old implementation
        // emitted one cycle per back-edge, including duplicates.
        Topology topology = new Topology(
                List.of(
                        TestTopologies.node("a", "service", "A"),
                        TestTopologies.node("b", "service", "B"),
                        TestTopologies.node("c", "service", "C")),
                List.of(
                        TestTopologies.edge("e1", "a", "b"),
                        TestTopologies.edge("e2", "b", "c"),
                        TestTopologies.edge("e3", "a", "c"),
                        TestTopologies.edge("e4", "b", "a"),
                        TestTopologies.edge("e5", "c", "a"),
                        TestTopologies.edge("e6", "c", "b")));

        List<List<String>> cycles = detector.detectCycles(topology);

        Set<List<String>> unique = new HashSet<>();
        for (List<String> cycle : cycles) {
            assertTrue(unique.add(cycle), "cycle reported more than once: " + cycle);
        }
        assertFalse(cycles.isEmpty(), "a complete graph must contain cycles");
    }

    @Test
    @DisplayName("is deterministic for the same input")
    void isDeterministic() {
        Topology topology = TestTopologies.withCycle();
        List<List<String>> first = detector.detectCycles(topology);

        for (int run = 0; run < 20; run++) {
            assertEquals(first, detector.detectCycles(topology), "run " + run + " differed");
        }
    }

    @Test
    @DisplayName("ignores self-loops, which EdgeValidator already reports")
    void ignoresSelfLoops() {
        // A self-loop would otherwise appear as a degenerate one-element cycle on top of
        // the SELF_LOOP error the user is already shown.
        CanvasNode a = TestTopologies.node("a", "service", "A");
        CanvasNode b = TestTopologies.node("b", "service", "B");
        Topology topology = new Topology(
                List.of(a, b),
                List.of(TestTopologies.edge("e1", "a", "a"), TestTopologies.edge("e2", "a", "b")));

        assertTrue(detector.detectCycles(topology).isEmpty(),
                "a self-loop is not a cycle for this detector; got " + detector.detectCycles(topology));
    }

    @Test
    @DisplayName("tolerates a null topology")
    void toleratesNull() {
        assertTrue(detector.detectCycles(null).isEmpty());
    }

    @Test
    @DisplayName("terminates on a dense graph")
    void terminatesOnDenseGraphs() {
        int n = 12;
        List<CanvasNode> nodes = new ArrayList<>();
        List<CanvasEdge> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            NodeData data = new NodeData("N" + i);
            nodes.add(new CanvasNode("n" + i, "service", null, data));
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    edges.add(new CanvasEdge("e" + i + "-" + j, "n" + i, "n" + j));
                }
            }
        }

        // The bounded enumeration is the point: an unbounded elementary-cycle search on a
        // complete 12-node graph would not terminate in useful time.
        List<List<String>> cycles = detector.detectCycles(new Topology(nodes, edges));

        assertTrue(cycles.size() <= CycleDetector.MAX_REPORTED_CYCLES,
                "the result must be bounded, got " + cycles.size());
        assertFalse(cycles.isEmpty(), "a complete graph must produce cycles");
    }
}
