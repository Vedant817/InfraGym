package com.archforge.graphcompiler.validation;

import com.archforge.graphcompiler.schema.ReactFlowEdge;
import com.archforge.graphcompiler.schema.ReactFlowNode;
import com.archforge.graphcompiler.schema.ReactFlowTopology;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class EdgeValidator {

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
        for (int i = 0; i < edges.size(); i++) {
            ReactFlowEdge edge = edges.get(i);
            String fieldPrefix = "edges[" + i + "]";

            if (edge == null) {
                result.addError(fieldPrefix, "NULL_EDGE", "Edge at index " + i + " is null", ValidationError.Severity.ERROR);
                continue;
            }

            if (edge.getId() == null || edge.getId().isBlank()) {
                result.addError(fieldPrefix + ".id", "MISSING_EDGE_ID", "Edge at index " + i + " is missing an ID", ValidationError.Severity.ERROR);
            } else if (!edgeIds.add(edge.getId())) {
                result.addError(fieldPrefix + ".id", "DUPLICATE_EDGE_ID", "Duplicate edge ID: '" + edge.getId() + "'", ValidationError.Severity.ERROR);
            }

            if (edge.getSource() == null || edge.getSource().isBlank()) {
                result.addError(fieldPrefix + ".source", "MISSING_EDGE_SOURCE", "Edge '" + edge.getId() + "' is missing a source", ValidationError.Severity.ERROR);
            } else if (!nodeIds.contains(edge.getSource())) {
                result.addError(fieldPrefix + ".source", "INVALID_EDGE_SOURCE",
                        "Edge '" + edge.getId() + "' references unknown source node '" + edge.getSource() + "'",
                        ValidationError.Severity.ERROR);
            }

            if (edge.getTarget() == null || edge.getTarget().isBlank()) {
                result.addError(fieldPrefix + ".target", "MISSING_EDGE_TARGET", "Edge '" + edge.getId() + "' is missing a target", ValidationError.Severity.ERROR);
            } else if (!nodeIds.contains(edge.getTarget())) {
                result.addError(fieldPrefix + ".target", "INVALID_EDGE_TARGET",
                        "Edge '" + edge.getId() + "' references unknown target node '" + edge.getTarget() + "'",
                        ValidationError.Severity.ERROR);
            }

            if (edge.getSource() != null && edge.getSource().equals(edge.getTarget())) {
                result.addError(fieldPrefix, "SELF_LOOP",
                        "Edge '" + edge.getId() + "' connects node '" + edge.getSource() + "' to itself",
                        ValidationError.Severity.ERROR);
            }
        }

        return result;
    }
}
