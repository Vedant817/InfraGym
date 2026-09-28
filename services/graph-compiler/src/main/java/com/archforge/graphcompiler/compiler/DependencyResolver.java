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
        List<String> topoOrder = graph.getTopologicalOrder();

        if (topoOrder == null) {
            for (String nodeId : graph.getNodeMap().keySet()) {
                result.put(nodeId, getUpstreamDependencies(nodeId, graph));
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

    public Map<String, Set<String>> getAllDownstreamDependencies(InternalGraph graph) {
        Map<String, Set<String>> result = new HashMap<>();
        List<String> topoOrder = graph.getTopologicalOrder();

        if (topoOrder == null) {
            for (String nodeId : graph.getNodeMap().keySet()) {
                result.put(nodeId, getDownstreamDependencies(nodeId, graph));
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
}
