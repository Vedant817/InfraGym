package com.archforge.graphcompiler.compiler;

import com.archforge.graphcompiler.exception.CompilationException;
import com.archforge.graphcompiler.exception.DAGCycleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Kahn's algorithm, producing a deterministic topological ordering.
 *
 * <p><b>Determinism.</b> Ready nodes are drawn from a
 * {@link PriorityQueue} ordered by node id rather than a plain {@link ArrayDeque}.
 * With a deque the result depends on {@code HashMap} iteration order, which varies
 * between JVM runs and between JDK versions — so the same design could compile to two
 * different DAGs, and the AI evaluator would report a different bottleneck each time.
 * Ordering by id makes the output reproducible.
 *
 * <p><b>Cycle reporting.</b> When the sort cannot consume every node, the remaining
 * nodes form one or more cycles. Rather than reporting only "produced N of M", the
 * algorithm extracts an actual cycle path so the error can name the nodes involved.
 */
@Slf4j
@Component
public class TopologicalSorter {

    /**
     * @return every node id, in dependency order
     * @throws DAGCycleException when the graph is not acyclic, naming a cycle
     * @throws CompilationException on an internal inconsistency
     */
    public List<String> sort(InternalGraph graph) {
        Map<String, Integer> inDegree = new HashMap<>();
        for (String nodeId : graph.getNodeMap().keySet()) {
            inDegree.putIfAbsent(nodeId, 0);
        }

        for (List<String> neighbours : graph.getAdjacencyList().values()) {
            for (String neighbour : neighbours) {
                inDegree.merge(neighbour, 1, Integer::sum);
            }
        }

        PriorityQueue<String> ready = new PriorityQueue<>(Comparator.naturalOrder());
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.offer(entry.getKey());
            }
        }

        List<String> sorted = new ArrayList<>(inDegree.size());
        while (!ready.isEmpty()) {
            String node = ready.poll();
            sorted.add(node);

            for (String neighbour : graph.getAdjacencyList().getOrDefault(node, Collections.emptyList())) {
                int remaining = inDegree.getOrDefault(neighbour, 1) - 1;
                inDegree.put(neighbour, remaining);
                if (remaining == 0) {
                    ready.offer(neighbour);
                }
            }
        }

        if (sorted.size() != graph.getNodeMap().size()) {
            List<String> cycle = findCycle(graph);
            log.warn("Topological sort consumed {} of {} nodes; cycle: {}",
                    sorted.size(), graph.getNodeMap().size(), cycle);
            throw new DAGCycleException(cycle);
        }

        return sorted;
    }

    /**
     * Extracts one concrete cycle from the unsorted remainder.
     *
     * <p>Iterative DFS restricted to nodes that Kahn's algorithm could not reach, which
     * is exactly the set belonging to at least one cycle. Used only on the error path.
     */
    private List<String> findCycle(InternalGraph graph) {
        Set<String> permanentlySorted = new HashSet<>();
        Set<String> inCycleRegion = new HashSet<>();

        // Recompute the region Kahn could not consume.
        Map<String, Integer> inDegree = new HashMap<>();
        graph.getNodeMap().keySet().forEach(id -> inDegree.put(id, 0));
        graph.getAdjacencyList().values().stream()
                .flatMap(List::stream)
                .forEach(n -> inDegree.merge(n, 1, Integer::sum));

        PriorityQueue<String> ready = new PriorityQueue<>(Comparator.naturalOrder());
        inDegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.offer(id);
            }
        });
        while (!ready.isEmpty()) {
            String node = ready.poll();
            permanentlySorted.add(node);
            for (String neighbour : graph.getAdjacencyList().getOrDefault(node, Collections.emptyList())) {
                int remaining = inDegree.getOrDefault(neighbour, 1) - 1;
                inDegree.put(neighbour, remaining);
                if (remaining == 0) {
                    ready.offer(neighbour);
                }
            }
        }
        inCycleRegion.addAll(graph.getNodeMap().keySet());
        inCycleRegion.removeAll(permanentlySorted);

        Deque<String> stack = new ArrayDeque<>();
        Set<String> onPath = new HashSet<>();
        Map<String, String> parent = new HashMap<>();

        List<String> sortedIds = new ArrayList<>(inCycleRegion);
        Collections.sort(sortedIds);

        for (String start : sortedIds) {
            if (parent.containsKey(start)) {
                continue;
            }
            List<String> cycle = dfsForCycle(start, graph, inCycleRegion, parent, stack, onPath);
            if (cycle != null) {
                return cycle;
            }
        }

        return sortedIds;
    }

    private List<String> dfsForCycle(String node,
                                     InternalGraph graph,
                                     Set<String> region,
                                     Map<String, String> parent,
                                     Deque<String> stack,
                                     Set<String> onPath) {
        stack.push(node);

        while (!stack.isEmpty()) {
            String current = stack.peek();
            if (onPath.add(current)) {
                parent.putIfAbsent(current, null);
            }

            boolean descended = false;
            for (String neighbour : graph.getAdjacencyList().getOrDefault(current, Collections.emptyList())) {
                if (!region.contains(neighbour)) {
                    continue;
                }
                if (onPath.contains(neighbour)) {
                    List<String> cycle = new ArrayList<>();
                    Deque<String> rebuild = new ArrayDeque<>();
                    String walk = current;
                    while (walk != null) {
                        rebuild.addFirst(walk);
                        walk = parent.get(walk);
                    }
                    cycle.addAll(rebuild);
                    cycle.add(neighbour);
                    stack.clear();
                    return cycle;
                }
                if (!parent.containsKey(neighbour)) {
                    parent.put(neighbour, current);
                    stack.push(neighbour);
                    descended = true;
                    break;
                }
            }

            if (!descended) {
                stack.pop();
                onPath.remove(current);
            }
        }

        return null;
    }
}
