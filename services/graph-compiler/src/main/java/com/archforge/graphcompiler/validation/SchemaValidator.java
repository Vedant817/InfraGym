package com.archforge.graphcompiler.validation;

import com.archforge.graphcompiler.schema.ReactFlowNode;
import com.archforge.graphcompiler.schema.ReactFlowTopology;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class SchemaValidator {

    private static final int MAX_NODES = 500;
    private static final int MAX_EDGES = 2000;

    public ValidationResult validate(ReactFlowTopology topology) {
        ValidationResult result = new ValidationResult();

        if (topology == null) {
            result.addError("topology", "NULL_TOPOLOGY", "Topology cannot be null", ValidationError.Severity.ERROR);
            return result;
        }

        List<ReactFlowNode> nodes = topology.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            result.addError("nodes", "EMPTY_TOPOLOGY", "Topology must contain at least 1 node", ValidationError.Severity.ERROR);
            return result;
        }

        if (nodes.size() > MAX_NODES) {
            result.addError("nodes", "TOO_MANY_NODES",
                    "Topology exceeds maximum of " + MAX_NODES + " nodes (found " + nodes.size() + ")",
                    ValidationError.Severity.ERROR);
        }

        if (topology.getEdges() != null && topology.getEdges().size() > MAX_EDGES) {
            result.addError("edges", "TOO_MANY_EDGES",
                    "Topology exceeds maximum of " + MAX_EDGES + " edges (found " + topology.getEdges().size() + ")",
                    ValidationError.Severity.ERROR);
        }

        Set<String> nodeIds = new HashSet<>();
        for (int i = 0; i < nodes.size(); i++) {
            ReactFlowNode node = nodes.get(i);
            String fieldPrefix = "nodes[" + i + "]";

            if (node == null) {
                result.addError(fieldPrefix, "NULL_NODE", "Node at index " + i + " is null", ValidationError.Severity.ERROR);
                continue;
            }

            if (node.getId() == null || node.getId().isBlank()) {
                result.addError(fieldPrefix + ".id", "MISSING_NODE_ID", "Node at index " + i + " is missing an ID", ValidationError.Severity.ERROR);
            } else if (!nodeIds.add(node.getId())) {
                result.addError(fieldPrefix + ".id", "DUPLICATE_NODE_ID", "Duplicate node ID: '" + node.getId() + "'", ValidationError.Severity.ERROR);
            }

            if (node.getType() == null || node.getType().isBlank()) {
                result.addError(fieldPrefix + ".type", "MISSING_NODE_TYPE", "Node '" + node.getId() + "' is missing a type", ValidationError.Severity.ERROR);
            }

            if (node.getData() == null) {
                result.addError(fieldPrefix + ".data", "MISSING_NODE_DATA", "Node '" + node.getId() + "' is missing data", ValidationError.Severity.ERROR);
            } else if (node.getData().getLabel() == null || node.getData().getLabel().isBlank()) {
                result.addError(fieldPrefix + ".data.label", "MISSING_NODE_LABEL", "Node '" + node.getId() + "' is missing a label", ValidationError.Severity.WARNING);
            }
        }

        return result;
    }
}
