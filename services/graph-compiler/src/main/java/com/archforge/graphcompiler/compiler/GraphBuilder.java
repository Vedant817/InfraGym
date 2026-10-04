package com.archforge.graphcompiler.compiler;

import com.archforge.contracts.canvas.CanvasEdge;
import com.archforge.contracts.canvas.CanvasNode;
import com.archforge.contracts.canvas.Topology;
import com.archforge.graphcompiler.exception.CompilationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a canvas topology into an adjacency structure.
 *
 * <p>Two defects in the previous version are fixed here.
 *
 * <p><b>1. A topology with no usable node ids produced an empty graph instead of
 * failing.</b> The old code checked {@code nodes.isEmpty()} and then filtered out
 * nodes with a null id when building the map, without re-checking the result. A
 * topology whose nodes all lacked ids built a graph with zero nodes, which flowed on
 * to a {@code CompiledGraph} reporting {@code totalNodes: 0} and was published as a
 * legitimate simulation task. The node count is now verified after filtering.
 *
 * <p><b>2. Rejected edges were dropped silently.</b> Self-loops, duplicate edges and
 * edges referencing non-existent nodes were all skipped with a {@code log.warn} and
 * no record in the graph. The compiler therefore produced a DAG that quietly differed
 * from what the user drew, and nothing downstream could tell. {@link RejectedEdge} now
 * carries each one so the caller can surface it.
 */
@Slf4j
@Component
public class GraphBuilder {

    /**
     * @throws CompilationException when the topology contains no usable node
     */
    public InternalGraph build(Topology topology) {
        if (topology == null) {
            throw new CompilationException("Topology cannot be null");
        }

        List<CanvasNode> nodes = topology.getNodes();
        if (nodes.isEmpty()) {
            throw new CompilationException("Topology must contain at least 1 node");
        }

        Map<String, CanvasNode> nodeMap = new LinkedHashMap<>();
        for (CanvasNode node : nodes) {
            if (node == null || node.getId() == null || node.getId().isBlank()) {
                continue;
            }
            nodeMap.put(node.getId(), node);
        }

        // The previous version never re-checked this: a topology whose nodes all had
        // null ids built a zero-node graph that was published as a valid task.
        if (nodeMap.isEmpty()) {
            throw new CompilationException(
                    "Topology contains " + nodes.size() + " node(s) but none has a usable id. "
                            + "Every node must have a unique, non-blank id.");
        }

        Map<String, Set<String>> adjacencySet = new LinkedHashMap<>();
        Map<String, List<String>> adjacencyList = new LinkedHashMap<>();
        Map<String, List<String>> reverseAdjacencyList = new LinkedHashMap<>();
        List<CanvasEdge> edges = new ArrayList<>();
        List<RejectedEdge> rejected = new ArrayList<>();

        for (String nodeId : nodeMap.keySet()) {
            adjacencySet.put(nodeId, new LinkedHashSet<>());
            reverseAdjacencyList.put(nodeId, new ArrayList<>());
        }

        int index = 0;
        for (CanvasEdge edge : topology.getEdges()) {
            String label = edgeLabel(edge, index++);

            if (edge == null) {
                rejected.add(new RejectedEdge(index - 1, label, "EDGE_IS_NULL", "Edge is null"));
                continue;
            }
            if (edge.getSource() == null || edge.getTarget() == null) {
                rejected.add(new RejectedEdge(index - 1, label, "EDGE_MISSING_ENDPOINT",
                        "Edge is missing a source or target"));
                continue;
            }
            if (edge.getSource().equals(edge.getTarget())) {
                rejected.add(new RejectedEdge(index - 1, label, "SELF_LOOP",
                        "Edge connects '" + edge.getSource() + "' to itself"));
                continue;
            }
            if (!nodeMap.containsKey(edge.getSource()) || !nodeMap.containsKey(edge.getTarget())) {
                String missing = !nodeMap.containsKey(edge.getSource()) ? edge.getSource() : edge.getTarget();
                rejected.add(new RejectedEdge(index - 1, label, "UNKNOWN_NODE",
                        "Edge references unknown node '" + missing + "'"));
                continue;
            }
            if (!adjacencySet.get(edge.getSource()).add(edge.getTarget())) {
                rejected.add(new RejectedEdge(index - 1, label, "DUPLICATE_EDGE",
                        "A connection from '" + edge.getSource() + "' to '" + edge.getTarget()
                                + "' already exists"));
                continue;
            }

            adjacencyList.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge.getTarget());
            reverseAdjacencyList.computeIfAbsent(edge.getTarget(), k -> new ArrayList<>()).add(edge.getSource());
            edges.add(edge);
        }

        if (!rejected.isEmpty()) {
            log.warn("Dropped {} of {} edge(s) during graph construction: {}",
                    rejected.size(), topology.getEdges().size(),
                    rejected.stream().map(RejectedEdge::reason).distinct().toList());
        }

        log.debug("Built graph: {} nodes, {} edges, {} rejected",
                nodeMap.size(), edges.size(), rejected.size());

        return InternalGraph.builder()
                .nodeMap(Collections.unmodifiableMap(nodeMap))
                .adjacencyList(Collections.unmodifiableMap(adjacencyList))
                .reverseAdjacencyList(Collections.unmodifiableMap(reverseAdjacencyList))
                .edges(Collections.unmodifiableList(edges))
                .rejectedEdges(Collections.unmodifiableList(rejected))
                .build();
    }

    private String edgeLabel(CanvasEdge edge, int index) {
        if (edge == null) {
            return "edges[" + index + "]";
        }
        if (edge.getId() != null) {
            return edge.getId();
        }
        return edge.getSource() + "->" + edge.getTarget();
    }

    /**
     * An edge the compiler refused to compile in.
     *
     * <p>Carried on {@link InternalGraph} so the controller can tell the user which
     * connection was discarded and why, instead of returning a DAG that silently
     * differs from the canvas.
     */
    public record RejectedEdge(int index, String label, String reason, String detail) {
    }
}
