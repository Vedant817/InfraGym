package com.archforge.graphcompiler.validation;

import com.archforge.graphcompiler.schema.ReactFlowEdge;
import com.archforge.graphcompiler.schema.ReactFlowNode;
import com.archforge.graphcompiler.schema.ReactFlowTopology;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class OrphanNodeDetector {

    public ValidationResult validate(ReactFlowTopology topology) {
        ValidationResult result = new ValidationResult();

        if (topology == null || topology.getNodes() == null) {
            return result;
        }

        List<ReactFlowNode> nodes = topology.getNodes();
        List<ReactFlowEdge> edges = topology.getEdges();

        if (nodes.size() <= 1) {
            return result;
        }

        Set<String> connectedNodes = new HashSet<>();
        if (edges != null) {
            for (ReactFlowEdge edge : edges) {
                if (edge != null) {
                    if (edge.getSource() != null) {
                        connectedNodes.add(edge.getSource());
                    }
                    if (edge.getTarget() != null) {
                        connectedNodes.add(edge.getTarget());
                    }
                }
            }
        }

        for (ReactFlowNode node : nodes) {
            if (node != null && node.getId() != null && !connectedNodes.contains(node.getId())) {
                String label = node.getData() != null && node.getData().getLabel() != null
                        ? node.getData().getLabel()
                        : "N/A";
                result.addError("nodes", "ORPHAN_NODE",
                        "Node '" + node.getId() + "' (" + label + ") is not connected to any other node. Connect it to the graph or remove it.",
                        ValidationError.Severity.WARNING);
            }
        }

        return result;
    }
}
