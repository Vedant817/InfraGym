package com.archforge.graphcompiler.compiler;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class GraphMetricsCalculator {

    public Map<String, InternalGraph.GraphMetrics> calculate(InternalGraph graph) {
        Map<String, InternalGraph.GraphMetrics> metrics = new HashMap<>();
        Map<String, Integer> depthMemo = new HashMap<>();

        Map<String, Set<String>> upstreamCounts = computeUpstreamCounts(graph);
        Map<String, Set<String>> downstreamCounts = computeDownstreamCounts(graph);

        for (String nodeId : graph.getNodeMap().keySet()) {
            int fanIn = graph.getReverseAdjacencyList().getOrDefault(nodeId, Collections.emptyList()).size();
            int fanOut = graph.getAdjacencyList().getOrDefault(nodeId, Collections.emptyList()).size();
            int depth = calculateDepth(nodeId, graph, depthMemo);
            int upstreamCount = upstreamCounts.getOrDefault(nodeId, Collections.emptySet()).size();
            int downstreamCount = downstreamCounts.getOrDefault(nodeId, Collections.emptySet()).size();

            metrics.put(nodeId, InternalGraph.GraphMetrics.builder()
                    .depth(depth)
                    .fanIn(fanIn)
                    .fanOut(fanOut)
                    .upstreamCount(upstreamCount)
                    .downstreamCount(downstreamCount)
                    .build());
        }

        return metrics;
    }

    public int calculateGraphDepth(InternalGraph graph) {
        int maxDepth = 0;
        Map<String, Integer> depthMemo = new HashMap<>();
        for (String nodeId : graph.getNodeMap().keySet()) {
            maxDepth = Math.max(maxDepth, calculateDepth(nodeId, graph, depthMemo));
        }
        return maxDepth;
    }

    public int calculateGraphBreadth(InternalGraph graph) {
        Map<Integer, Integer> levelCounts = new HashMap<>();
        Map<String, Integer> depthMemo = new HashMap<>();

        for (String nodeId : graph.getNodeMap().keySet()) {
            int depth = calculateDepth(nodeId, graph, depthMemo);
            levelCounts.merge(depth, 1, Integer::sum);
        }

        return levelCounts.values().stream().max(Integer::compareTo).orElse(0);
    }

    public List<String> findCriticalPath(InternalGraph graph) {
        if (graph.getTopologicalOrder() == null) {
            return Collections.emptyList();
        }

        Map<String, Integer> longestPath = new HashMap<>();
        Map<String, String> predecessor = new HashMap<>();

        for (String nodeId : graph.getTopologicalOrder()) {
            longestPath.put(nodeId, 0);
            predecessor.put(nodeId, null);
        }

        for (String nodeId : graph.getTopologicalOrder()) {
            int currentLength = longestPath.get(nodeId);
            for (String neighbor : graph.getAdjacencyList().getOrDefault(nodeId, Collections.emptyList())) {
                if (longestPath.get(neighbor) < currentLength + 1) {
                    longestPath.put(neighbor, currentLength + 1);
                    predecessor.put(neighbor, nodeId);
                }
            }
        }

        String endNode = null;
        int maxLength = 0;
        for (Map.Entry<String, Integer> entry : longestPath.entrySet()) {
            if (entry.getValue() > maxLength) {
                maxLength = entry.getValue();
                endNode = entry.getKey();
            }
        }

        List<String> path = new ArrayList<>();
        String current = endNode;
        while (current != null) {
            path.add(0, current);
            current = predecessor.get(current);
        }

        return path;
    }

    private Map<String, Set<String>> computeUpstreamCounts(InternalGraph graph) {
        Map<String, Set<String>> result = new HashMap<>();
        List<String> topoOrder = graph.getTopologicalOrder();

        if (topoOrder == null) {
            for (String nodeId : graph.getNodeMap().keySet()) {
                result.put(nodeId, new HashSet<>());
            }
            return result;
        }

        for (String nodeId : topoOrder) {
            Set<String> upstream = new HashSet<>();
            for (String pred : graph.getReverseAdjacencyList().getOrDefault(nodeId, Collections.emptyList())) {
                upstream.add(pred);
                upstream.addAll(result.get(pred));
            }
            result.put(nodeId, upstream);
        }

        return result;
    }

    private Map<String, Set<String>> computeDownstreamCounts(InternalGraph graph) {
        Map<String, Set<String>> result = new HashMap<>();
        List<String> topoOrder = graph.getTopologicalOrder();

        if (topoOrder == null) {
            for (String nodeId : graph.getNodeMap().keySet()) {
                result.put(nodeId, new HashSet<>());
            }
            return result;
        }

        List<String> reversedTopo = new ArrayList<>(topoOrder);
        Collections.reverse(reversedTopo);

        for (String nodeId : reversedTopo) {
            Set<String> downstream = new HashSet<>();
            for (String successor : graph.getAdjacencyList().getOrDefault(nodeId, Collections.emptyList())) {
                downstream.add(successor);
                downstream.addAll(result.get(successor));
            }
            result.put(nodeId, downstream);
        }

        return result;
    }

    private int calculateDepth(String nodeId, InternalGraph graph, Map<String, Integer> memo) {
        if (memo.containsKey(nodeId)) {
            return memo.get(nodeId);
        }

        int maxDepth = 0;
        for (String predecessor : graph.getReverseAdjacencyList().getOrDefault(nodeId, Collections.emptyList())) {
            maxDepth = Math.max(maxDepth, calculateDepth(predecessor, graph, memo) + 1);
        }

        memo.put(nodeId, maxDepth);
        return maxDepth;
    }
}
