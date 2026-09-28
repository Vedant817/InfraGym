package com.archforge.graphcompiler.validation;

import com.archforge.graphcompiler.schema.ReactFlowEdge;
import com.archforge.graphcompiler.schema.ReactFlowNode;
import com.archforge.graphcompiler.schema.ReactFlowTopology;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class CycleDetector {

    public List<List<String>> detectCycles(ReactFlowTopology topology) {
        List<List<String>> cycles = new ArrayList<>();

        if (topology == null || topology.getNodes() == null || topology.getEdges() == null) {
            return cycles;
        }

        Map<String, List<String>> adjacencyList = buildAdjacencyList(topology);
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        List<String> path = new ArrayList<>();
        Map<String, Integer> pathIndex = new HashMap<>();

        for (String node : adjacencyList.keySet()) {
            if (!visited.contains(node)) {
                dfsIterative(node, adjacencyList, visited, recursionStack, path, pathIndex, cycles);
            }
        }

        return cycles;
    }

    private void dfsIterative(String startNode, Map<String, List<String>> adjacencyList,
                               Set<String> visited, Set<String> recursionStack,
                               List<String> path, Map<String, Integer> pathIndex,
                               List<List<String>> cycles) {
        Deque<String> stack = new ArrayDeque<>();
        stack.push(startNode);

        while (!stack.isEmpty()) {
            String node = stack.peek();

            if (!visited.contains(node)) {
                visited.add(node);
                recursionStack.add(node);
                path.add(node);
                pathIndex.put(node, path.size() - 1);
            }

            List<String> neighbors = adjacencyList.getOrDefault(node, Collections.emptyList());
            boolean hasUnvisitedNeighbor = false;

            for (String neighbor : neighbors) {
                if (!visited.contains(neighbor)) {
                    stack.push(neighbor);
                    hasUnvisitedNeighbor = true;
                    break;
                } else if (recursionStack.contains(neighbor)) {
                    int cycleStart = pathIndex.get(neighbor);
                    List<String> cycle = new ArrayList<>(path.subList(cycleStart, path.size()));
                    cycle.add(neighbor);
                    cycles.add(cycle);
                }
            }

            if (!hasUnvisitedNeighbor) {
                stack.pop();
                recursionStack.remove(node);
                path.remove(path.size() - 1);
                pathIndex.remove(node);
            }
        }
    }

    private Map<String, List<String>> buildAdjacencyList(ReactFlowTopology topology) {
        Map<String, List<String>> adjacencyList = new HashMap<>();

        for (ReactFlowNode node : topology.getNodes()) {
            if (node != null && node.getId() != null) {
                adjacencyList.putIfAbsent(node.getId(), new ArrayList<>());
            }
        }

        for (ReactFlowEdge edge : topology.getEdges()) {
            if (edge != null && edge.getSource() != null && edge.getTarget() != null) {
                adjacencyList.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge.getTarget());
            }
        }

        return adjacencyList;
    }
}
