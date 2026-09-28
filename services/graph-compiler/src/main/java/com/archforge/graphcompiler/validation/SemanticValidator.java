package com.archforge.graphcompiler.validation;

import com.archforge.graphcompiler.schema.NodeType;
import com.archforge.graphcompiler.schema.ReactFlowNode;
import com.archforge.graphcompiler.schema.ReactFlowTopology;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SemanticValidator {

    public ValidationResult validate(ReactFlowTopology topology) {
        ValidationResult result = new ValidationResult();

        if (topology == null || topology.getNodes() == null || topology.getNodes().isEmpty()) {
            return result;
        }

        List<ReactFlowNode> nodes = topology.getNodes();

        boolean hasEntryPoint = nodes.stream()
                .filter(n -> n != null && n.getType() != null)
                .anyMatch(n -> {
                    try {
                        NodeType type = NodeType.fromValue(n.getType());
                        return type == NodeType.LOAD_BALANCER || type == NodeType.GATEWAY || type == NodeType.CDN;
                    } catch (Exception e) {
                        return false;
                    }
                });

        if (!hasEntryPoint) {
            result.addError("topology", "NO_ENTRY_POINT",
                    "Topology has no entry point. Add a load balancer, gateway, or CDN node to represent how traffic enters the system.",
                    ValidationError.Severity.WARNING);
        }

        boolean hasComputeNode = nodes.stream()
                .filter(n -> n != null && n.getType() != null)
                .anyMatch(n -> {
                    try {
                        NodeType type = NodeType.fromValue(n.getType());
                        return type.isCompute();
                    } catch (Exception e) {
                        return false;
                    }
                });

        if (!hasComputeNode) {
            result.addError("topology", "NO_COMPUTE_NODE",
                    "Topology has no compute node. Add a service, worker, or function node to represent processing logic.",
                    ValidationError.Severity.WARNING);
        }

        return result;
    }
}
