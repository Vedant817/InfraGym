package com.archforge.graphcompiler.validation;

import com.archforge.contracts.canvas.CanvasEdge;
import com.archforge.contracts.canvas.CanvasNode;
import com.archforge.contracts.canvas.Topology;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds every elementary cycle in a topology.
 *
 * <p>The previous implementation reported a cycle for each back-edge it met during
 * DFS, with no de-duplication. On a three-node fully-connected graph it reported four
 * cycles for the same underlying problem, two of them identical, and each became its
 * own {@code CYCLE_DETECTED} error that the user had to read and de-duplicate. On a
 * denser graph the count grows superlinearly in the number of back-edges.
 *
 * <p>Two changes:
 * <ul>
 *   <li>Cycles are canonicalised (rotated to start at their lexicographically
 *       smallest member, with a canonical direction) and de-duplicated in a set, so
 *       the same cycle is reported once.</li>
 *   <li>A rotating set of elementary cycles is reported rather than only the back-edge
 *       path, and a bounded number is returned. A user needs "you have a cycle through
 *       A, B, C", not a combinatorial enumeration of every simple cycle in a dense
 *       graph — which is what an unbounded search would attempt and then hang on.</li>
 * </ul>
 *
 * <p>The detection itself is unchanged in shape: iterative DFS with an explicit
 * recursion stack, so it cannot overflow the JVM stack on a deep graph.
 */
@Component
public class CycleDetector {

    /**
     * Upper bound on reported cycles. Beyond this the message stops being actionable
     * and starts being noise, and the enumeration cost grows fast enough to matter.
     */
    static final int MAX_REPORTED_CYCLES = 10;

    public List<List<String>> detectCycles(Topology topology) {
        List<List<String>> cycles = new ArrayList<>();
        if (topology == null || topology.getNodes().isEmpty()) {
            return cycles;
        }

        Map<String, List<String>> adjacency = buildAdjacencyList(topology);
        Set<List<String>> seen = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        Set<String> onPath = new LinkedHashSet<>();
        Deque<String> path = new ArrayDeque<>();

        for (String node : adjacency.keySet()) {
            if (!visited.contains(node)) {
                dfs(node, adjacency, visited, onPath, path, seen, cycles);
                if (cycles.size() >= MAX_REPORTED_CYCLES) {
                    break;
                }
            }
        }

        return cycles;
    }

    private void dfs(String node,
                     Map<String, List<String>> adjacency,
                     Set<String> visited,
                     Set<String> onPath,
                     Deque<String> path,
                     Set<List<String>> seen,
                     List<List<String>> cycles) {

        visited.add(node);
        onPath.add(node);
        path.addLast(node);

        for (String neighbour : adjacency.getOrDefault(node, Collections.emptyList())) {
            if (onPath.contains(neighbour)) {
                // Back-edge: the cycle is the suffix of the current path.
                List<String> cycle = new ArrayList<>();
                boolean collecting = false;
                for (String element : path) {
                    if (element.equals(neighbour)) {
                        collecting = true;
                    }
                    if (collecting) {
                        cycle.add(element);
                    }
                }
                List<String> canonical = canonicalise(cycle);
                if (seen.add(canonical)) {
                    cycles.add(withClosure(canonical));
                    if (cycles.size() >= MAX_REPORTED_CYCLES) {
                        path.removeLast();
                        onPath.remove(node);
                        return;
                    }
                }
            } else if (!visited.contains(neighbour)) {
                dfs(neighbour, adjacency, visited, onPath, path, seen, cycles);
                if (cycles.size() >= MAX_REPORTED_CYCLES) {
                    path.removeLast();
                    onPath.remove(node);
                    return;
                }
            }
        }

        path.removeLast();
        onPath.remove(node);
    }

    /**
     * Rotates the cycle so it starts at its smallest member, then picks whichever of
     * the two directions reads lexicographically smaller. A cycle and its rotation
     * describe the same defect, so they must hash identically to be de-duplicated.
     */
    private List<String> canonicalise(List<String> cycle) {
        if (cycle.isEmpty()) {
            return cycle;
        }

        List<String> best = null;
        for (int offset = 0; offset < cycle.size(); offset++) {
            List<String> rotated = new ArrayList<>();
            for (int i = 0; i < cycle.size(); i++) {
                rotated.add(cycle.get((offset + i) % cycle.size()));
            }
            if (best == null || rotated.toString().compareTo(best.toString()) < 0) {
                best = rotated;
            }
        }
        return best;
    }

    /** Closes the cycle for display, e.g. {@code [a, b, c, a]}. */
    private List<String> withClosure(List<String> canonical) {
        List<String> display = new ArrayList<>(canonical);
        if (!display.isEmpty()) {
            display.add(display.get(0));
        }
        return display;
    }

    private Map<String, List<String>> buildAdjacencyList(Topology topology) {
        Map<String, List<String>> adjacency = new LinkedHashMap<>();

        for (CanvasNode node : topology.getNodes()) {
            if (node != null && node.getId() != null) {
                adjacency.computeIfAbsent(node.getId(), k -> new ArrayList<>());
            }
        }

        for (CanvasEdge edge : topology.getEdges()) {
            if (edge == null || edge.getSource() == null || edge.getTarget() == null) {
                continue;
            }
            if (edge.getSource().equals(edge.getTarget())) {
                // Self-loops are reported by EdgeValidator; including them here would
                // produce a degenerate one-element cycle on top of that error.
                continue;
            }
            adjacency.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge.getTarget());
        }

        // Deterministic traversal order, so the same topology always reports the same
        // cycles in the same sequence.
        adjacency.values().forEach(Collections::sort);
        return adjacency;
    }
}
