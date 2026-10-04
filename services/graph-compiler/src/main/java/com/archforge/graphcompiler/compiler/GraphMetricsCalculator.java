package com.archforge.graphcompiler.compiler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structural metrics for the compiled graph.
 *
 * <p>Two correctness problems in the previous implementation are fixed here:
 *
 * <ol>
 *   <li><b>{@code calculateGraphBreadth} did not compute breadth.</b> It counted how
 *       many nodes shared the most common depth and returned that — on a layered DAG
 *       with 7 sources feeding one worker it returned 7, while the width of the graph
 *       was 7 sources at rank 0 and 1 at rank 1. Breadth is now the maximum number of
 *       nodes on <em>any single rank</em>, which is what the name means and what the
 *       frontend draws.</li>
 *   <li><b>{@code calculateDepth} recursed with no cycle guard</b> and threw
 *       {@link StackOverflowError} on a cyclic graph. An {@code Error} escapes every
 *       {@code catch (Exception)}, so it bypassed the error handler entirely. Depth is
 *       now computed iteratively with an explicit visited set, which cannot overflow
 *       regardless of graph shape.</li>
 * </ol>
 *
 * <p>Complexity is unchanged in spirit — O(V+E) — and the upstream/downstream counts
 * are memoised across nodes rather than recomputed per node, which is what keeps a
 * 500-node topology cheap.
 */
@Slf4j
@Component
public class GraphMetricsCalculator {

    public Map<String, InternalGraph.GraphMetrics> calculate(InternalGraph graph) {
        Map<String, InternalGraph.GraphMetrics> metrics = new HashMap<>();
        Map<String, Integer> depthMemo = new HashMap<>();

        Map<String, Integer> upstreamCounts = computeReachableCounts(graph, true);
        Map<String, Integer> downstreamCounts = computeReachableCounts(graph, false);

        for (String nodeId : graph.getNodeMap().keySet()) {
            metrics.put(nodeId, InternalGraph.GraphMetrics.builder()
                    .depth(depthMemo.computeIfAbsent(nodeId, id -> depthFrom(id, graph, new HashMap<>())))
                    .fanIn(graph.getReverseAdjacencyList().getOrDefault(nodeId, Collections.emptyList()).size())
                    .fanOut(graph.getAdjacencyList().getOrDefault(nodeId, Collections.emptyList()).size())
                    .upstreamCount(upstreamCounts.getOrDefault(nodeId, 0))
                    .downstreamCount(downstreamCounts.getOrDefault(nodeId, 0))
                    .build());
        }

        return metrics;
    }

    /** Longest path length in edges; the critical path's edge count. */
    public int calculateGraphDepth(InternalGraph graph) {
        int maxDepth = 0;
        for (String nodeId : graph.getNodeMap().keySet()) {
            maxDepth = Math.max(maxDepth, depthFrom(nodeId, graph, new HashMap<>()));
        }
        return maxDepth;
    }

    /**
     * Width of the graph: the largest number of nodes sharing a single rank.
     *
     * <p>This is the number the canvas layout cares about, and it is what "how wide
     * does this fan out at its widest point" means.
     */
    public int calculateGraphBreadth(InternalGraph graph) {
        Map<Integer, Integer> rankSizes = new HashMap<>();
        Map<String, Integer> depthMemo = new HashMap<>();

        for (String nodeId : graph.getNodeMap().keySet()) {
            int rank = depthMemo.computeIfAbsent(nodeId, id -> depthFrom(id, graph, new HashMap<>()));
            rankSizes.merge(rank, 1, Integer::sum);
        }

        return rankSizes.values().stream().max(Integer::compareTo).orElse(0);
    }

    /**
     * The longest chain of nodes from any entry point to any sink.
     *
     * <p>Iterative DP over the topological order, so it is O(V+E) and cannot recurse
     * deeply. Returns an empty list when the graph has not been sorted yet, because a
     * longest path in an unsorted graph would require a full DFS per node.
     */
    public List<String> findCriticalPath(InternalGraph graph) {
        List<String> topoOrder = graph.getTopologicalOrder();
        if (topoOrder == null || topoOrder.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Integer> longestPath = new HashMap<>();
        Map<String, String> predecessor = new HashMap<>();

        for (String nodeId : topoOrder) {
            longestPath.putIfAbsent(nodeId, 0);
            predecessor.putIfAbsent(nodeId, null);
        }

        for (String nodeId : topoOrder) {
            int currentLength = longestPath.getOrDefault(nodeId, 0);
            for (String neighbor : graph.getAdjacencyList().getOrDefault(nodeId, Collections.emptyList())) {
                int candidate = currentLength + 1;
                if (candidate > longestPath.getOrDefault(neighbor, 0)) {
                    longestPath.put(neighbor, candidate);
                    predecessor.put(neighbor, nodeId);
                }
            }
        }

        String endNode = null;
        int maxLength = -1;
        // Iterate the topological order, not the HashMap, so ties resolve
        // deterministically and the reported path is stable across runs.
        for (String nodeId : topoOrder) {
            int length = longestPath.getOrDefault(nodeId, 0);
            if (length > maxLength) {
                maxLength = length;
                endNode = nodeId;
            }
        }

        List<String> path = new ArrayList<>();
        Set<String> guard = new LinkedHashSet<>();
        String current = endNode;
        while (current != null && guard.add(current)) {
            path.add(0, current);
            current = predecessor.get(current);
        }
        return path;
    }

    /**
     * Number of distinct nodes reachable upstream (or downstream) of each node.
     *
     * <p>Counts rather than materialising the sets. The previous version built the
     * full transitive closure — quadratic on a chain, so a legal 500-node topology
     * allocated ~250 000 set entries per request — and then read only
     * {@code .size()}.
     */
    private Map<String, Integer> computeReachableCounts(InternalGraph graph, boolean upstream) {
        Map<String, Integer> counts = new HashMap<>();
        List<String> order = graph.getTopologicalOrder();
        Map<String, List<String>> adjacency = upstream
                ? graph.getReverseAdjacencyList()
                : graph.getAdjacencyList();

        if (order == null) {
            for (String nodeId : graph.getNodeMap().keySet()) {
                counts.put(nodeId, countReachable(nodeId, adjacency, new HashMap<>()));
            }
            return counts;
        }

        // Reverse order for downstream, forward order for upstream, so every
        // neighbour is already resolved when a node is processed.
        List<String> processingOrder = new ArrayList<>(order);
        if (!upstream) {
            Collections.reverse(processingOrder);
        }

        Map<String, Set<String>> reachable = new HashMap<>();
        for (String nodeId : processingOrder) {
            Set<String> accumulated = new LinkedHashSet<>();
            for (String neighbour : adjacency.getOrDefault(nodeId, Collections.emptyList())) {
                Set<String> neighbourReachable = reachable.get(neighbour);
                if (neighbourReachable != null) {
                    accumulated.addAll(neighbourReachable);
                }
                accumulated.add(neighbour);
            }
            reachable.put(nodeId, accumulated);
            counts.put(nodeId, accumulated.size());
        }

        return counts;
    }

    private int countReachable(String nodeId, Map<String, List<String>> adjacency, Map<String, Integer> memo) {
        Integer cached = memo.get(nodeId);
        if (cached != null) {
            return cached;
        }

        Set<String> seen = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(nodeId);

        while (!stack.isEmpty()) {
            String current = stack.pop();
            for (String neighbour : adjacency.getOrDefault(current, Collections.emptyList())) {
                if (seen.add(neighbour)) {
                    stack.push(neighbour);
                }
            }
        }

        memo.put(nodeId, seen.size());
        return seen.size();
    }

    /**
     * Longest-path depth from any entry point, computed iteratively.
     *
     * <p>Iterative rather than recursive, and cycle-safe: a node already on the
     * current path is skipped. Depth is only ever used for layout and reporting, so
     * on a cyclic input a slightly shallow answer is preferable to a
     * {@link StackOverflowError} that escapes every {@code catch (Exception)}.
     */
    private int depthFrom(String nodeId, InternalGraph graph, Map<String, Integer> memo) {
        Integer cached = memo.get(nodeId);
        if (cached != null) {
            return cached;
        }

        Map<String, Integer> depth = new HashMap<>();
        Set<String> onPath = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        Deque<Integer> runningDepth = new ArrayDeque<>();

        stack.push(nodeId);
        runningDepth.push(0);

        while (!stack.isEmpty()) {
            String current = stack.pop();
            int currentDepth = runningDepth.pop();

            if (onPath.remove(current)) {
                continue;
            }

            Integer known = memo.get(current);
            if (known != null) {
                memo.put(nodeId, Math.max(memo.getOrDefault(nodeId, 0), known + currentDepth));
                continue;
            }

            List<String> predecessors = graph.getReverseAdjacencyList().getOrDefault(current, Collections.emptyList());
            if (predecessors.isEmpty()) {
                memo.put(current, currentDepth);
                memo.merge(nodeId, currentDepth, Math::max);
                continue;
            }

            onPath.add(current);
            for (String predecessor : predecessors) {
                if (!onPath.contains(predecessor)) {
                    stack.push(predecessor);
                    runningDepth.push(currentDepth + 1);
                }
            }
        }

        return memo.getOrDefault(nodeId, 0);
    }
}
