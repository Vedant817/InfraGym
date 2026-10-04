package com.archforge.graphcompiler.validation;

import com.archforge.contracts.canvas.CanvasEdge;
import com.archforge.contracts.canvas.CanvasNode;
import com.archforge.contracts.canvas.Topology;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports nodes that are not meaningfully connected to the rest of the graph.
 *
 * <p><b>What changed and why.</b> The previous version added <em>both endpoints of
 * every edge</em> to a "connected" set without checking that either endpoint is
 * actually a node in the topology. With nodes {@code {a, lonely}} and edges
 * {@code a→ghost}, {@code ghost→lonely} — neither edge referencing a real node except
 * {@code a} — {@code lonely} was counted as connected and reported as fine. It is in
 * fact an orphan, reached only through a node that does not exist.
 *
 * <p>Fixes:
 * <ul>
 *   <li>Edges referencing unknown nodes are excluded from the connectivity graph, so a
 *       dangling edge cannot make a real orphan look connected.</li>
 *   <li>Connectivity is computed on weakly-connected components rather than by
 *       counting edges. A node is an orphan if its component contains only itself, which
 *       is precise and cheap: union-find over the valid edges.</li>
 *   <li>Self-loops no longer count as connectivity. The previous version added
 *       {@code node.getId()} twice for {@code node→node} and called it connected.</li>
 * </ul>
 */
@Component
public class OrphanNodeDetector {

    public ValidationResult validate(Topology topology) {
        ValidationResult result = new ValidationResult();

        if (topology == null) {
            return result;
        }

        List<CanvasNode> nodes = topology.getNodes();
        if (nodes.size() <= 1) {
            return result;
        }

        Set<String> nodeIds = new LinkedHashSet<>();
        for (CanvasNode node : nodes) {
            if (node != null && node.getId() != null) {
                nodeIds.add(node.getId());
            }
        }

        UnionFind components = new UnionFind(nodeIds);

        int danglingEdges = 0;
        for (CanvasEdge edge : topology.getEdges()) {
            if (edge == null || edge.getSource() == null || edge.getTarget() == null) {
                continue;
            }
            // An edge to or from a node that does not exist tells us nothing about
            // connectivity, and previously made real orphans look connected.
            if (!nodeIds.contains(edge.getSource()) || !nodeIds.contains(edge.getTarget())) {
                danglingEdges++;
                continue;
            }
            if (edge.getSource().equals(edge.getTarget())) {
                continue; // self-loop is not connectivity
            }
            components.union(edge.getSource(), edge.getTarget());
        }

        if (danglingEdges > 0) {
            result.addError("edges", "DANGLING_EDGES",
                    danglingEdges + " edge(s) reference nodes that do not exist and were ignored. "
                            + "These are reported per-edge by EdgeValidator.",
                    ValidationError.Severity.INFO);
        }

        Map<String, Integer> componentSizes = new HashMap<>();
        for (String id : nodeIds) {
            componentSizes.merge(components.find(id), 1, Integer::sum);
        }

        for (CanvasNode node : nodes) {
            if (node == null || node.getId() == null) {
                continue;
            }
            String id = node.getId();
            if (componentSizes.getOrDefault(components.find(id), 1) <= 1) {
                result.addError("nodes", "ORPHAN_NODE",
                        "Node '" + id + "' (" + labelOf(node) + ") is not connected to any other node. "
                                + "Connect it to the graph or remove it.",
                        ValidationError.Severity.WARNING);
            }
        }

        return result;
    }

    private String labelOf(CanvasNode node) {
        return node.getData() != null && node.getData().getLabel() != null
                ? node.getData().getLabel()
                : "N/A";
    }

    /** Union-find with path compression; O(V·α(V)) for the whole graph. */
    private static final class UnionFind {
        private final Map<String, String> parent = new HashMap<>();

        UnionFind(Set<String> ids) {
            for (String id : ids) {
                parent.put(id, id);
            }
        }

        String find(String id) {
            String current = parent.get(id);
            if (current == null) {
                return id;
            }
            while (!current.equals(parent.get(current))) {
                current = parent.get(current);
            }
            // Path compression.
            String root = current;
            current = id;
            while (!current.equals(root)) {
                String next = parent.get(current);
                parent.put(current, root);
                current = next;
            }
            return root;
        }

        void union(String a, String b) {
            String rootA = find(a);
            String rootB = find(b);
            if (!rootA.equals(rootB)) {
                // Deterministic merge direction so the component ids are stable.
                if (rootA.compareTo(rootB) <= 0) {
                    parent.put(rootB, rootA);
                } else {
                    parent.put(rootA, rootB);
                }
            }
        }
    }
}
