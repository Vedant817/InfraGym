package com.archforge.graphcompiler.compiler;

import com.archforge.graphcompiler.schema.ReactFlowEdge;
import com.archforge.graphcompiler.schema.ReactFlowNode;
import com.archforge.graphcompiler.schema.ReactFlowTopology;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class GraphBuilder {

    public InternalGraph build(ReactFlowTopology topology) {
        Map<String, ReactFlowNode> nodeMap = new HashMap<>();
        Map<String, List<String>> adjacencyList = new HashMap<>();
        Map<String, List<String>> reverseAdjacencyList = new HashMap<>();
        List<ReactFlowEdge> edges = new ArrayList<>();

        for (ReactFlowNode node : topology.getNodes()) {
            if (node != null && node.getId() != null) {
                nodeMap.put(node.getId(), node);
                adjacencyList.putIfAbsent(node.getId(), new ArrayList<>());
                reverseAdjacencyList.putIfAbsent(node.getId(), new ArrayList<>());
            }
        }

        if (topology.getEdges() != null) {
            for (ReactFlowEdge edge : topology.getEdges()) {
                if (edge != null && edge.getSource() != null && edge.getTarget() != null) {
                    if (nodeMap.containsKey(edge.getSource()) && nodeMap.containsKey(edge.getTarget())) {
                        adjacencyList.get(edge.getSource()).add(edge.getTarget());
                        reverseAdjacencyList.get(edge.getTarget()).add(edge.getSource());
                        edges.add(edge);
                    }
                }
            }
        }

        return InternalGraph.builder()
                .nodeMap(nodeMap)
                .adjacencyList(adjacencyList)
                .reverseAdjacencyList(reverseAdjacencyList)
                .edges(edges)
                .build();
    }
}
