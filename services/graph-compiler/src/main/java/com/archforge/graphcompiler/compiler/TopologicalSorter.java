package com.archforge.graphcompiler.compiler;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class TopologicalSorter {

    public List<String> sort(InternalGraph graph) {
        Map<String, Integer> inDegree = new HashMap<>();
        for (String nodeId : graph.getNodeMap().keySet()) {
            inDegree.put(nodeId, 0);
        }

        for (List<String> neighbors : graph.getAdjacencyList().values()) {
            for (String neighbor : neighbors) {
                inDegree.merge(neighbor, 1, Integer::sum);
            }
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String node = queue.poll();
            sorted.add(node);

            for (String neighbor : graph.getAdjacencyList().getOrDefault(node, Collections.emptyList())) {
                int newDegree = inDegree.get(neighbor) - 1;
                inDegree.put(neighbor, newDegree);
                if (newDegree == 0) {
                    queue.offer(neighbor);
                }
            }
        }

        return sorted;
    }
}
