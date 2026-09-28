package com.archforge.graphcompiler.validation;

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
                    String type = n.getType().toLowerCase();
                    return type.equals("load_balancer") || type.equals("gateway") || type.equals("cdn");
                });

        if (!hasEntryPoint) {
            result.addError("topology", "NO_ENTRY_POINT",
                    "Topology has no entry point. Add a load balancer, gateway, or CDN node to represent how traffic enters the system.",
                    ValidationError.Severity.WARNING);
        }

        boolean hasComputeNode = nodes.stream()
                .filter(n -> n != null && n.getType() != null)
                .anyMatch(n -> {
                    String type = n.getType().toLowerCase();
                    return type.equals("service") || type.equals("worker") || type.equals("function");
                });

        if (!hasComputeNode) {
            result.addError("topology", "NO_COMPUTE_NODE",
                    "Topology has no compute node. Add a service, worker, or function node to represent processing logic.",
                    ValidationError.Severity.WARNING);
        }

        return result;
    }
}
