package com.archforge.graphcompiler.compiler;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class DependencyResolver {

    public Set<String> getUpstreamDependencies(String nodeId, InternalGraph graph) {
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

        return visited;
    }

    public Set<String> getDownstreamDependencies(String nodeId, InternalGraph graph) {
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

        return visited;
    }

    public Map<String, Set<String>> getAllUpstreamDependencies(InternalGraph graph) {
        Map<String, Set<String>> result = new HashMap<>();
        for (String nodeId : graph.getNodeMap().keySet()) {
            result.put(nodeId, getUpstreamDependencies(nodeId, graph));
        }
        return result;
    }

    public Map<String, Set<String>> getAllDownstreamDependencies(InternalGraph graph) {
        Map<String, Set<String>> result = new HashMap<>();
        for (String nodeId : graph.getNodeMap().keySet()) {
            result.put(nodeId, getDownstreamDependencies(nodeId, graph));
        }
        return result;
    }
}
