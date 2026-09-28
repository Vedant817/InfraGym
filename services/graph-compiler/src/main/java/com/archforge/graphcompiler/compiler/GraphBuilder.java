package com.archforge.graphcompiler.compiler;

import com.archforge.graphcompiler.exception.CompilationException;
import com.archforge.graphcompiler.schema.ReactFlowEdge;
import com.archforge.graphcompiler.schema.ReactFlowNode;
import com.archforge.graphcompiler.schema.ReactFlowTopology;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class GraphBuilder {

    public InternalGraph build(ReactFlowTopology topology) {
        if (topology == null) {
            throw new CompilationException("Topology cannot be null");
        }

        List<ReactFlowNode> nodes = topology.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            throw new CompilationException("Topology must contain at least 1 node");
        }

        Map<String, ReactFlowNode> nodeMap = new LinkedHashMap<>();
        Map<String, Set<String>> adjacencySet = new LinkedHashMap<>();
        Map<String, List<String>> adjacencyList = new LinkedHashMap<>();
        Map<String, List<String>> reverseAdjacencyList = new LinkedHashMap<>();
        List<ReactFlowEdge> edges = new ArrayList<>();
        Set<String> droppedEdges = new HashSet<>();

        for (ReactFlowNode node : nodes) {
            if (node != null && node.getId() != null) {
                nodeMap.put(node.getId(), node);
                adjacencySet.putIfAbsent(node.getId(), new LinkedHashSet<>());
                reverseAdjacencyList.putIfAbsent(node.getId(), new ArrayList<>());
            }
        }

        if (topology.getEdges() != null) {
            for (ReactFlowEdge edge : topology.getEdges()) {
                if (edge == null || edge.getSource() == null || edge.getTarget() == null) {
                    continue;
                }

                if (edge.getSource().equals(edge.getTarget())) {
                    log.warn("Self-loop detected on node {}, skipping", edge.getSource());
                    continue;
                }

                if (!nodeMap.containsKey(edge.getSource()) || !nodeMap.containsKey(edge.getTarget())) {
                    droppedEdges.add(edge.getId() != null ? edge.getId() : edge.getSource() + "->" + edge.getTarget());
                    continue;
                }

                if (adjacencySet.get(edge.getSource()).add(edge.getTarget())) {
                    adjacencyList.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge.getTarget());
                    reverseAdjacencyList.computeIfAbsent(edge.getTarget(), k -> new ArrayList<>()).add(edge.getSource());
                    edges.add(edge);
                } else {
                    log.warn("Duplicate edge from {} to {}, skipping", edge.getSource(), edge.getTarget());
                }
            }
        }

        if (!droppedEdges.isEmpty()) {
            log.warn("Dropped {} edges referencing non-existent nodes", droppedEdges.size());
        }

        log.debug("Built internal graph with {} nodes and {} edges", nodeMap.size(), edges.size());

        return InternalGraph.builder()
                .nodeMap(Collections.unmodifiableMap(nodeMap))
                .adjacencyList(Collections.unmodifiableMap(adjacencyList))
                .reverseAdjacencyList(Collections.unmodifiableMap(reverseAdjacencyList))
                .edges(Collections.unmodifiableList(edges))
                .build();
    }
}
