package com.archforge.graphcompiler.validation;

import com.archforge.graphcompiler.exception.DAGCycleException;
import com.archforge.graphcompiler.schema.ReactFlowEdge;
import com.archforge.graphcompiler.schema.ReactFlowNode;
import com.archforge.graphcompiler.schema.ReactFlowTopology;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class CycleDetector {

    public void detectCycles(ReactFlowTopology topology) {
        if (topology == null || topology.getNodes() == null || topology.getEdges() == null) {
            return;
        }

        Map<String, List<String>> adjacencyList = buildAdjacencyList(topology);
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        List<String> path = new ArrayList<>();

        for (String node : adjacencyList.keySet()) {
            if (!visited.contains(node)) {
                List<String> cycle = dfs(node, adjacencyList, visited, recursionStack, path);
                if (cycle != null) {
                    throw new DAGCycleException(cycle);
                }
            }
        }
    }

    private List<String> dfs(String node, Map<String, List<String>> adjacencyList,
                             Set<String> visited, Set<String> recursionStack, List<String> path) {
        visited.add(node);
        recursionStack.add(node);
        path.add(node);

        List<String> neighbors = adjacencyList.getOrDefault(node, Collections.emptyList());
        for (String neighbor : neighbors) {
            if (!visited.contains(neighbor)) {
                List<String> cycle = dfs(neighbor, adjacencyList, visited, recursionStack, path);
                if (cycle != null) {
                    return cycle;
                }
            } else if (recursionStack.contains(neighbor)) {
                int cycleStart = path.indexOf(neighbor);
                List<String> cycle = new ArrayList<>(path.subList(cycleStart, path.size()));
                cycle.add(neighbor);
                return cycle;
            }
        }

        recursionStack.remove(node);
        path.remove(path.size() - 1);
        return null;
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
