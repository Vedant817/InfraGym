package com.archforge.graphcompiler.compiler;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class GraphMetricsCalculator {

    public Map<String, InternalGraph.GraphMetrics> calculate(InternalGraph graph) {
        Map<String, InternalGraph.GraphMetrics> metrics = new HashMap<>();

        for (String nodeId : graph.getNodeMap().keySet()) {
            int fanIn = graph.getReverseAdjacencyList().getOrDefault(nodeId, Collections.emptyList()).size();
            int fanOut = graph.getAdjacencyList().getOrDefault(nodeId, Collections.emptyList()).size();
            int depth = calculateDepth(nodeId, graph);
            int upstreamCount = countUpstream(nodeId, graph);
            int downstreamCount = countDownstream(nodeId, graph);

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
        for (String nodeId : graph.getNodeMap().keySet()) {
            maxDepth = Math.max(maxDepth, calculateDepth(nodeId, graph));
        }
        return maxDepth;
    }

    public int calculateGraphBreadth(InternalGraph graph) {
        int maxBreadth = 0;
        for (List<String> neighbors : graph.getAdjacencyList().values()) {
            maxBreadth = Math.max(maxBreadth, neighbors.size());
        }
        return maxBreadth;
    }

    public List<String> findCriticalPath(InternalGraph graph) {
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

    private int calculateDepth(String nodeId, InternalGraph graph) {
        int maxDepth = 0;
        for (String predecessor : graph.getReverseAdjacencyList().getOrDefault(nodeId, Collections.emptyList())) {
            maxDepth = Math.max(maxDepth, calculateDepth(predecessor, graph) + 1);
        }
        return maxDepth;
    }

    private int countUpstream(String nodeId, InternalGraph graph) {
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(nodeId);

        while (!stack.isEmpty()) {
            String current = stack.pop();
            for (String predecessor : graph.getReverseAdjacencyList().getOrDefault(current, Collections.emptyList())) {
                if (visited.add(predecessor)) {
                    stack.push(predecessor);
                }
            }
        }

        return visited.size();
    }

    private int countDownstream(String nodeId, InternalGraph graph) {
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(nodeId);

        while (!stack.isEmpty()) {
            String current = stack.pop();
            for (String successor : graph.getAdjacencyList().getOrDefault(current, Collections.emptyList())) {
                if (visited.add(successor)) {
                    stack.push(successor);
                }
            }
        }

        return visited.size();
    }
}
