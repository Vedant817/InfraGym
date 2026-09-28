package com.archforge.graphcompiler.validation;

import com.archforge.graphcompiler.schema.NodeType;
import com.archforge.graphcompiler.schema.ReactFlowNode;
import com.archforge.graphcompiler.schema.ReactFlowTopology;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class NodeTypeValidator {

    public ValidationResult validate(ReactFlowTopology topology) {
        ValidationResult result = new ValidationResult();

        if (topology == null || topology.getNodes() == null) {
            return result;
        }

        List<ReactFlowNode> nodes = topology.getNodes();
        Set<String> validTypes = NodeType.getAllValues();

        for (int i = 0; i < nodes.size(); i++) {
            ReactFlowNode node = nodes.get(i);
            if (node == null || node.getId() == null) {
                continue;
            }

            String fieldPrefix = "nodes[" + i + "]";
            String type = node.getType();

            if (type == null || type.isBlank()) {
                continue;
            }

            try {
                NodeType.fromValue(type);
            } catch (Exception e) {
                result.addError(fieldPrefix + ".type", "INVALID_NODE_TYPE",
                        "Node '" + node.getId() + "' has unknown type '" + type + "'. Valid types: " + validTypes,
                        ValidationError.Severity.ERROR);
            }
        }

        return result;
    }
}
