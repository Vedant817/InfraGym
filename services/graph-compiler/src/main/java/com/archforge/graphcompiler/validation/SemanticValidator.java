package com.archforge.graphcompiler.validation;

import com.archforge.contracts.canvas.CanvasNode;
import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.canvas.Topology;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Design-level checks that need the whole graph in view.
 *
 * <p>Every check here is a warning, never an error. A missing entry point or an
 * unreachable component is exactly the kind of mistake this platform exists to
 * surface — rejecting the submission would prevent the user from getting the
 * simulation feedback that would have explained the problem.
 *
 * <p>Two substantive additions over the previous version:
 * <ul>
 *   <li><b>Unreachable components.</b> The previous orphan check only looked for nodes
 *       with no edges at all. A component that is connected internally but has no path
 *       from any entry point — an entire dead subsystem — was reported as fine. That is
 *       the more common and more consequential mistake.</li>
 *   <li><b>Single points of failure.</b> One compute node behind one load balancer, or
 *       one storage node with no replica, is a design fact the AI evaluator will
 *       comment on. Saying so at validation time, where the user can act on it, is
 *       more useful than saying it three stages later.</li>
 * </ul>
 */
@Component
public class SemanticValidator {

    public ValidationResult validate(Topology topology) {
        ValidationResult result = new ValidationResult();

        if (topology == null || topology.getNodes().isEmpty()) {
            return result;
        }

        List<CanvasNode> nodes = topology.getNodes();

        Set<String> entryPointIds = new LinkedHashSet<>();
        Set<String> computeIds = new LinkedHashSet<>();
        Set<String> storageIds = new LinkedHashSet<>();
        Set<String> networkIds = new LinkedHashSet<>();

        for (CanvasNode node : nodes) {
            if (node == null) {
                continue;
            }
            NodeType type = NodeType.findByValue(node.getType());
            if (type == null) {
                continue;
            }
            if (type.canBeEntryPoint()) {
                entryPointIds.add(node.getId());
            }
            if (type.isCompute()) {
                computeIds.add(node.getId());
            }
            if (type.isStorage()) {
                storageIds.add(node.getId());
            }
            if (type.isNetwork()) {
                networkIds.add(node.getId());
            }
        }

        if (entryPointIds.isEmpty()) {
            result.addError("topology", "NO_ENTRY_POINT",
                    "No entry point found. Add a load balancer, gateway or CDN node to show how traffic "
                            + "enters the system — without one, the simulation cannot generate load.",
                    ValidationError.Severity.WARNING);
        }

        if (computeIds.isEmpty()) {
            result.addError("topology", "NO_COMPUTE_NODE",
                    "No compute node found. Add a service, worker or function node to represent the "
                            + "processing logic.",
                    ValidationError.Severity.WARNING);
        }

        if (storageIds.isEmpty()) {
            result.addError("topology", "NO_STORAGE_NODE",
                    "No storage node found. Most architectures need somewhere to persist state; "
                            + "without one the simulation cannot measure read or write load.",
                    ValidationError.Severity.WARNING);
        }

        warnOnUnreachableComponents(topology, entryPointIds, result);
        warnOnSinglePointsOfFailure(computeIds, storageIds, networkIds, result);

        return result;
    }

    /**
     * Reports components that cannot be reached from any entry point.
     *
     * <p>A reverse BFS from every entry point covers all of them in one pass, so a
     * design with several entry points is handled correctly rather than analysing each
     * in isolation.
     */
    private void warnOnUnreachableComponents(Topology topology,
                                            Set<String> entryPointIds,
                                            ValidationResult result) {
        if (entryPointIds.isEmpty()) {
            // Already reported as NO_ENTRY_POINT; reachability is meaningless without one.
            return;
        }

        java.util.Map<String, List<String>> upstream = new java.util.HashMap<>();
        for (CanvasNode node : topology.getNodes()) {
            if (node != null && node.getId() != null) {
                upstream.computeIfAbsent(node.getId(), k -> new ArrayList<>());
            }
        }
        for (var edge : topology.getEdges()) {
            if (edge != null && edge.getSource() != null && edge.getTarget() != null) {
                upstream.computeIfAbsent(edge.getTarget(), k -> new ArrayList<>()).add(edge.getSource());
            }
        }

        Set<String> reachable = new LinkedHashSet<>(entryPointIds);
        java.util.Deque<String> queue = new java.util.ArrayDeque<>(entryPointIds);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String predecessor : upstream.getOrDefault(current, List.of())) {
                if (reachable.add(predecessor)) {
                    queue.add(predecessor);
                }
            }
        }

        for (CanvasNode node : topology.getNodes()) {
            if (node == null || node.getId() == null) {
                continue;
            }
            if (!reachable.contains(node.getId())) {
                result.addError("nodes", "UNREACHABLE_NODE",
                        "Node '" + node.getId() + "' (" + labelOf(node)
                                + ") cannot be reached from any entry point. Traffic entering the system "
                                + "will never touch it.",
                        ValidationError.Severity.WARNING);
            }
        }
    }

    /**
     * Flags architectures whose availability is bounded by a single component.
     *
     * <p>Only reported when there is genuinely one of a kind. Two load balancers or two
     * databases is redundancy; one is a single point of failure, and saying so here is
     * far more actionable than waiting for the AI to notice.
     */
    private void warnOnSinglePointsOfFailure(Set<String> computeIds,
                                             Set<String> storageIds,
                                             Set<String> networkIds,
                                             ValidationResult result) {
        if (computeIds.size() == 1) {
            result.addError("topology", "SINGLE_COMPUTE_NODE",
                    "Only one compute node ('" + computeIds.iterator().next() + "') is present. If it fails, "
                            + "the whole system is unavailable. Add redundancy or accept the risk knowingly.",
                    ValidationError.Severity.WARNING);
        }
        if (storageIds.size() == 1) {
            result.addError("topology", "SINGLE_STORAGE_NODE",
                    "Only one storage node ('" + storageIds.iterator().next() + "') is present. Storage is "
                            + "usually the hardest component to make highly available; consider a replica.",
                    ValidationError.Severity.INFO);
        }
        if (networkIds.size() == 1) {
            result.addError("topology", "SINGLE_NETWORK_NODE",
                    "Only one traffic-distribution node ('" + networkIds.iterator().next()
                            + "') is present. It is a single point of failure for all inbound traffic.",
                    ValidationError.Severity.INFO);
        }
    }

    private String labelOf(CanvasNode node) {
        if (node.getData() == null || node.getData().getLabel() == null) {
            return "N/A";
        }
        return node.getData().getLabel();
    }
}
