package com.archforge.graphcompiler.compiler;

import com.archforge.graphcompiler.support.TestTopologies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GraphMetricsCalculator}.
 *
 * <p>This class replaces the previous suite, which asserted nothing meaningful about
 * breadth, depth or the critical path — so the breadth defect below shipped. Every test
 * here is built from a fixture whose true answer is known by construction, and the
 * assertions state that answer explicitly rather than checking a range.
 */
class GraphMetricsCalculatorTest {

    private GraphBuilder graphBuilder;
    private TopologicalSorter sorter;
    private GraphMetricsCalculator calculator;

    @BeforeEach
    void setUp() {
        graphBuilder = new GraphBuilder();
        sorter = new TopologicalSorter();
        calculator = new GraphMetricsCalculator();
    }

    @Test
    @DisplayName("reports the widest rank, not the most common one")
    void breadthIsTheWidestRank() {
        // Ranks: [root]=1, [leaf0..leaf5]=6, [sink]=1. True breadth is 6.
        InternalGraph graph = sorted(TestTopologies.wideTail());

        assertEquals(6, calculator.calculateGraphBreadth(graph),
                "breadth must be the largest number of nodes on any single rank");
    }

    @Test
    @DisplayName("reports the longest path length in edges")
    void depthIsTheLongestPath() {
        // chain(4) is n0 -> n1 -> n2 -> n3: three edges.
        InternalGraph graph = sorted(TestTopologies.chain(4));

        assertEquals(3, calculator.calculateGraphDepth(graph),
                "depth must be the longest path measured in edges");
    }

    @Test
    @DisplayName("depth of a single node is zero")
    void depthOfSingleNodeIsZero() {
        InternalGraph graph = graphBuilder.build(new com.archforge.contracts.canvas.Topology(
                java.util.List.of(TestTopologies.node("only", "service", "Only")),
                java.util.List.of()));

        assertEquals(0, calculator.calculateGraphDepth(graph));
    }

    @Test
    @DisplayName("finds the critical path through a diamond")
    void findsCriticalPathThroughDiamond() {
        // lb -> api -> {cache, db}: the longest chain is lb -> api -> db, length 3.
        InternalGraph graph = sorted(TestTopologies.validThreeTier());

        var path = calculator.findCriticalPath(graph);

        assertTrue(path.contains("lb"), "the critical path must start at the entry point, got " + path);
        assertTrue(path.contains("api"), "the critical path must pass through the API, got " + path);
        assertEquals(3, path.size(), "lb -> api -> db is three nodes, got " + path);
    }

    @Test
    @DisplayName("critical path is empty when the graph is unsorted")
    void criticalPathNeedsATopologicalOrder() {
        InternalGraph graph = graphBuilder.build(TestTopologies.validThreeTier());

        assertTrue(calculator.findCriticalPath(graph).isEmpty(),
                "without a topological order a longest path is not computed");
    }

    @Test
    @DisplayName("counts reachable nodes rather than materialising the closure")
    void countsUpstreamAndDownstream() {
        InternalGraph graph = sorted(TestTopologies.validThreeTier());
        Map<String, InternalGraph.GraphMetrics> metrics = calculator.calculate(graph);

        // cache and db both have api upstream, and lb upstream of that.
        assertEquals(2, metrics.get("cache").getUpstreamCount(),
                "cache has api and lb upstream");
        assertEquals(0, metrics.get("cache").getDownstreamCount(),
                "cache is a leaf");
        assertEquals(0, metrics.get("lb").getUpstreamCount(),
                "lb has nothing upstream");
        // downstreamCount is the transitive closure, not the direct edge count: lb can
        // reach api, and through api the cache and the database.
        assertEquals(3, metrics.get("lb").getDownstreamCount(),
                "lb reaches the api, and through it the cache and the database");
        assertEquals(2, metrics.get("api").getDownstreamCount(),
                "api reaches cache and db, so two distinct nodes");
    }

    @Test
    @DisplayName("reports fan-in and fan-out from the adjacency lists")
    void reportsFanInAndFanOut() {
        // LB -> api -> {cache, db}: api has one incoming edge and two outgoing ones.
        InternalGraph graph = sorted(TestTopologies.validThreeTier());
        Map<String, InternalGraph.GraphMetrics> metrics = calculator.calculate(graph);

        assertEquals(1, metrics.get("api").getFanIn(), "only the load balancer feeds the API");
        assertEquals(2, metrics.get("api").getFanOut(), "the API feeds both the cache and the database");
        assertEquals(0, metrics.get("lb").getFanIn(),
                "the load balancer is an entry point, so nothing feeds it");
        assertEquals(1, metrics.get("lb").getFanOut(), "the load balancer feeds the API");
        assertEquals(0, metrics.get("db").getFanOut(), "the database is a leaf");
    }

    @Test
    @DisplayName("terminates on a cyclic graph instead of overflowing the stack")
    void survivesCycles() {
        // Regression: depth was computed by unbounded recursion, so a cyclic graph threw
        // StackOverflowError. An Error escapes every catch (Exception), so it bypassed
        // the error handler and killed the listener thread.
        InternalGraph graph = graphBuilder.build(TestTopologies.withCycle());

        assertDoesNotThrow(() -> calculator.calculateGraphDepth(graph),
                "depth calculation must not recurse without bound");
        assertDoesNotThrow(() -> calculator.calculate(graph),
                "per-node metrics must not recurse without bound");
    }

    @Test
    @DisplayName("handles a deep chain without overflowing")
    void handlesDeepChain() {
        assertDoesNotThrow(() -> calculator.calculateGraphDepth(sorted(TestTopologies.chain(2000))),
                "a 2000-node chain must be handled iteratively");
    }

    @Test
    @DisplayName("is deterministic for a given graph")
    void isDeterministic() {
        InternalGraph graph = sorted(TestTopologies.wideTail());

        Map<String, Integer> first = breadthByNode(graph);
        for (int run = 0; run < 20; run++) {
            assertEquals(first, breadthByNode(graph), "run " + run + " differed");
        }
    }

    private Map<String, Integer> breadthByNode(InternalGraph graph) {
        Map<String, Integer> depths = new HashMap<>();
        graph.getNodeMap().keySet().forEach(id ->
                depths.put(id, calculator.calculateGraphDepth(graph)));
        return depths;
    }

    private InternalGraph sorted(com.archforge.contracts.canvas.Topology topology) {
        InternalGraph graph = graphBuilder.build(topology);
        graph.setTopologicalOrder(sorter.sort(graph));
        return graph;
    }
}
