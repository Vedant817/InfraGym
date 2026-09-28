package com.archforge.graphcompiler.validation;

import com.archforge.graphcompiler.schema.ReactFlowEdge;
import com.archforge.graphcompiler.schema.ReactFlowNode;
import com.archforge.graphcompiler.schema.ReactFlowTopology;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class EdgeValidator {

    private static final Set<String> VALID_EDGE_TYPES = Set.of(
            "default", "straight", "step", "smoothstep", "simplebezier"
    );

    public ValidationResult validate(ReactFlowTopology topology) {
        ValidationResult result = new ValidationResult();

        if (topology == null) {
            return result;
        }

        List<ReactFlowNode> nodes = topology.getNodes();
        List<ReactFlowEdge> edges = topology.getEdges();

        if (edges == null || edges.isEmpty()) {
            return result;
        }

        Set<String> nodeIds = new HashSet<>();
        if (nodes != null) {
            for (ReactFlowNode node : nodes) {
                if (node != null && node.getId() != null) {
                    nodeIds.add(node.getId());
                }
            }
        }

        Set<String> edgeIds = new HashSet<>();
        Set<String> seenConnections = new HashSet<>();

        for (int i = 0; i < edges.size(); i++) {
            ReactFlowEdge edge = edges.get(i);
            String fieldPrefix = "edges[" + i + "]";

            if (edge == null) {
                result.addError(fieldPrefix, "NULL_EDGE", "Edge at index " + i + " is null", ValidationError.Severity.ERROR);
                continue;
            }

            if (edge.getId() == null || edge.getId().isBlank()) {
                result.addError(fieldPrefix + ".id", "MISSING_EDGE_ID", "Edge at index " + i + " is missing an ID. Every edge must have a unique identifier.", ValidationError.Severity.ERROR);
            } else if (!edgeIds.add(edge.getId())) {
                result.addError(fieldPrefix + ".id", "DUPLICATE_EDGE_ID", "Duplicate edge ID: '" + edge.getId() + "'. Edge IDs must be unique.", ValidationError.Severity.ERROR);
            }

            if (edge.getSource() == null || edge.getSource().isBlank()) {
                result.addError(fieldPrefix + ".source", "MISSING_EDGE_SOURCE", "Edge '" + edge.getId() + "' is missing a source. Set the source node for this edge.", ValidationError.Severity.ERROR);
            } else if (!nodeIds.contains(edge.getSource())) {
                result.addError(fieldPrefix + ".source", "INVALID_EDGE_SOURCE",
                        "Edge '" + edge.getId() + "' references unknown source node '" + edge.getSource() + "'. Ensure the source node exists in the topology.",
                        ValidationError.Severity.ERROR);
            }

            if (edge.getTarget() == null || edge.getTarget().isBlank()) {
                result.addError(fieldPrefix + ".target", "MISSING_EDGE_TARGET", "Edge '" + edge.getId() + "' is missing a target. Set the target node for this edge.", ValidationError.Severity.ERROR);
            } else if (!nodeIds.contains(edge.getTarget())) {
                result.addError(fieldPrefix + ".target", "INVALID_EDGE_TARGET",
                        "Edge '" + edge.getId() + "' references unknown target node '" + edge.getTarget() + "'. Ensure the target node exists in the topology.",
                        ValidationError.Severity.ERROR);
            }

            if (edge.getSource() != null && edge.getSource().equals(edge.getTarget())) {
                result.addError(fieldPrefix, "SELF_LOOP",
                        "Edge '" + edge.getId() + "' connects node '" + edge.getSource() + "' to itself. Self-loops are not allowed in a DAG.",
                        ValidationError.Severity.ERROR);
            }

            if (edge.getType() != null && !edge.getType().isBlank() && !VALID_EDGE_TYPES.contains(edge.getType().toLowerCase())) {
                result.addError(fieldPrefix + ".type", "INVALID_EDGE_TYPE",
                        "Edge '" + edge.getId() + "' has unknown type '" + edge.getType() + "'. Valid types: " + String.join(", ", VALID_EDGE_TYPES),
                        ValidationError.Severity.WARNING);
            }

            if (edge.getSource() != null && edge.getTarget() != null && !edge.getSource().equals(edge.getTarget())) {
                String connectionKey = edge.getSource() + "->" + edge.getTarget();
                if (!seenConnections.add(connectionKey)) {
                    result.addError(fieldPrefix, "DUPLICATE_EDGE",
                            "Duplicate edge from [" + edge.getSource() + "] to [" + edge.getTarget() + "]. Multiple edges between the same nodes are not allowed.",
                            ValidationError.Severity.WARNING);
                }
            }
        }

        return result;
    }
}
