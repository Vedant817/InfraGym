package com.archforge.graphcompiler.validation;

import com.archforge.contracts.canvas.CanvasEdge;
import com.archforge.contracts.canvas.CanvasNode;
import com.archforge.contracts.canvas.Topology;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates connections between nodes.
 *
 * <p>The previous {@code VALID_EDGE_TYPES} set omitted {@code bezier} and
 * {@code animated}, both of which are stock React Flow edge types. A canvas using
 * them therefore produced one {@code INVALID_EDGE_TYPE} warning <em>per edge</em> —
 * up to 2 000 warnings on a max-size topology, each interpolated into an exception
 * message that then travels to the browser.
 *
 * <p>Two changes beyond widening the set:
 * <ul>
 *   <li>Edge <em>rendering</em> type is not a semantic property of the architecture.
 *       It is a presentational hint, so an unrecognised value is now reported once as an
 *       {@code INFO} note rather than once per edge as a warning. A real duplicate or
 *       dangling connection is still a warning.</li>
 *   <li>Findings are capped. A validator that emits thousands of near-identical
 *       messages has stopped communicating; the first few are enough to locate the
 *       problem.</li>
 * </ul>
 */
@Component
public class EdgeValidator {

    /**
     * React Flow edge renderer names we recognise. Not exhaustive by design — an
     * unknown renderer is cosmetic and reported as a note, not a warning.
     */
    private static final Set<String> KNOWN_EDGE_RENDERERS = Set.of(
            "default", "straight", "step", "smoothstep", "simplebezier",
            "bezier", "animated");

    /** Per-code cap so one class of problem cannot flood the response. */
    private static final int MAX_REPORTS_PER_CODE = 5;

    public ValidationResult validate(Topology topology) {
        ValidationResult result = new ValidationResult();

        if (topology == null) {
            return result;
        }

        List<CanvasNode> nodes = topology.getNodes();
        List<CanvasEdge> edges = topology.getEdges();

        if (edges.isEmpty()) {
            return result;
        }

        Set<String> nodeIds = new HashSet<>();
        for (CanvasNode node : nodes) {
            if (node != null && node.getId() != null) {
                nodeIds.add(node.getId());
            }
        }

        Set<String> edgeIds = new HashSet<>();
        Set<String> seenConnections = new HashSet<>();
        ReportBudget budget = new ReportBudget();

        for (int i = 0; i < edges.size(); i++) {
            CanvasEdge edge = edges.get(i);
            String fieldPrefix = "edges[" + i + "]";

            if (edge == null) {
                budget.add(result, fieldPrefix, "NULL_EDGE",
                        "Edge at index " + i + " is null", ValidationError.Severity.ERROR);
                continue;
            }

            String id = edge.getId();

            if (id == null || id.isBlank()) {
                budget.add(result, fieldPrefix + ".id", "MISSING_EDGE_ID",
                        "Edge at index " + i + " is missing an ID. Every edge must have a unique identifier.",
                        ValidationError.Severity.ERROR);
            } else if (!edgeIds.add(id)) {
                budget.add(result, fieldPrefix + ".id", "DUPLICATE_EDGE_ID",
                        "Duplicate edge ID: '" + id + "'. Edge IDs must be unique.", ValidationError.Severity.ERROR);
            }

            if (edge.getSource() == null || edge.getSource().isBlank()) {
                budget.add(result, fieldPrefix + ".source", "MISSING_EDGE_SOURCE",
                        "Edge '" + id + "' is missing a source.", ValidationError.Severity.ERROR);
            } else if (!nodeIds.contains(edge.getSource())) {
                budget.add(result, fieldPrefix + ".source", "INVALID_EDGE_SOURCE",
                        "Edge '" + id + "' references unknown source node '" + edge.getSource()
                                + "'. Ensure the source node exists in the topology.",
                        ValidationError.Severity.ERROR);
            }

            if (edge.getTarget() == null || edge.getTarget().isBlank()) {
                budget.add(result, fieldPrefix + ".target", "MISSING_EDGE_TARGET",
                        "Edge '" + id + "' is missing a target.", ValidationError.Severity.ERROR);
            } else if (!nodeIds.contains(edge.getTarget())) {
                budget.add(result, fieldPrefix + ".target", "INVALID_EDGE_TARGET",
                        "Edge '" + id + "' references unknown target node '" + edge.getTarget()
                                + "'. Ensure the target node exists in the topology.",
                        ValidationError.Severity.ERROR);
            }

            if (edge.getSource() != null && edge.getSource().equals(edge.getTarget())) {
                budget.add(result, fieldPrefix, "SELF_LOOP",
                        "Edge '" + id + "' connects node '" + edge.getSource()
                                + "' to itself. Self-loops are not allowed in a DAG.",
                        ValidationError.Severity.ERROR);
            }

            if (edge.getType() != null && !edge.getType().isBlank()
                    && !KNOWN_EDGE_RENDERERS.contains(edge.getType().toLowerCase(Locale.ROOT))) {
                // Cosmetic: the renderer name has no bearing on the compiled graph.
                budget.add(result, fieldPrefix + ".type", "UNKNOWN_EDGE_RENDERER",
                        "Edge '" + id + "' uses unrecognised renderer '" + edge.getType()
                                + "'. This affects display only and is ignored during compilation.",
                        ValidationError.Severity.INFO);
            }

            if (edge.getSource() != null && edge.getTarget() != null
                    && !edge.getSource().equals(edge.getTarget())) {
                if (!seenConnections.add(edge.getSource() + "->" + edge.getTarget())) {
                    budget.add(result, fieldPrefix, "DUPLICATE_EDGE",
                            "Duplicate edge from [" + edge.getSource() + "] to [" + edge.getTarget()
                                    + "']. Only one connection per node pair is compiled.",
                            ValidationError.Severity.WARNING);
                }
            }
        }

        return result;
    }

    /** Caps how many findings share a code, and says so once the cap is hit. */
    private static final class ReportBudget {
        private final java.util.Map<String, Integer> counts = new java.util.HashMap<>();

        void add(ValidationResult result, String field, String code, String message, ValidationError.Severity severity) {
            int seen = counts.merge(code, 1, Integer::sum);
            if (seen <= MAX_REPORTS_PER_CODE) {
                result.addError(field, code, message, severity);
            } else if (seen == MAX_REPORTS_PER_CODE + 1) {
                result.addError(field, code + "_TRUNCATED",
                        "Further '" + code + "' findings were suppressed after " + MAX_REPORTS_PER_CODE
                                + " occurrences.", ValidationError.Severity.INFO);
            }
        }
    }
}
