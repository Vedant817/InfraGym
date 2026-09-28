package com.archforge.graphcompiler.validation;

import com.archforge.graphcompiler.schema.ReactFlowNode;
import com.archforge.graphcompiler.schema.ReactFlowTopology;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class SchemaValidator {

    @Value("${graph-compiler.validation.max-nodes:500}")
    private int maxNodes;

    @Value("${graph-compiler.validation.max-edges:2000}")
    private int maxEdges;

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

        if (nodes.size() > maxNodes) {
            result.addError("nodes", "TOO_MANY_NODES",
                    "Topology exceeds maximum of " + maxNodes + " nodes (found " + nodes.size() + "). Remove " + (nodes.size() - maxNodes) + " nodes or contact support to raise the limit.",
                    ValidationError.Severity.ERROR);
        }

        if (topology.getEdges() != null && topology.getEdges().size() > maxEdges) {
            result.addError("edges", "TOO_MANY_EDGES",
                    "Topology exceeds maximum of " + maxEdges + " edges (found " + topology.getEdges().size() + "). Remove " + (topology.getEdges().size() - maxEdges) + " edges or contact support to raise the limit.",
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
                result.addError(fieldPrefix + ".id", "MISSING_NODE_ID", "Node at index " + i + " is missing an ID. Every node must have a unique identifier.", ValidationError.Severity.ERROR);
            } else if (!nodeIds.add(node.getId())) {
                result.addError(fieldPrefix + ".id", "DUPLICATE_NODE_ID", "Duplicate node ID: '" + node.getId() + "'. Node IDs must be unique. Rename one of the nodes with this ID.", ValidationError.Severity.ERROR);
            }

            if (node.getType() == null || node.getType().isBlank()) {
                result.addError(fieldPrefix + ".type", "MISSING_NODE_TYPE", "Node '" + node.getId() + "' is missing a type. Set a type for this node.", ValidationError.Severity.ERROR);
            }

            if (node.getData() == null) {
                result.addError(fieldPrefix + ".data", "MISSING_NODE_DATA", "Node '" + node.getId() + "' is missing data. Add data to this node.", ValidationError.Severity.ERROR);
            } else if (node.getData().getLabel() == null || node.getData().getLabel().isBlank()) {
                result.addError(fieldPrefix + ".data.label", "MISSING_NODE_LABEL", "Node '" + node.getId() + "' is missing a label. Add a label to identify this node.", ValidationError.Severity.WARNING);
            }

            if (node.getPosition() == null) {
                result.addError(fieldPrefix + ".position", "MISSING_NODE_POSITION", "Node '" + node.getId() + "' is missing a position. This may cause rendering issues.", ValidationError.Severity.WARNING);
            }

            if (node.getParentId() != null && !nodeIds.contains(node.getParentId())) {
                boolean parentExists = nodes.stream()
                        .anyMatch(n -> n != null && node.getParentId().equals(n.getId()));
                if (!parentExists) {
                    result.addError(fieldPrefix + ".parentId", "INVALID_PARENT_ID",
                            "Node '" + node.getId() + "' references parent '" + node.getParentId() + "' which does not exist in the topology.",
                            ValidationError.Severity.ERROR);
                }
            }
        }

        return result;
    }
}
