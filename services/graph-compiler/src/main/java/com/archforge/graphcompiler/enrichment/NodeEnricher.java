package com.archforge.graphcompiler.enrichment;

import com.archforge.graphcompiler.compiler.InternalGraph;
import com.archforge.graphcompiler.schema.NodeType;
import com.archforge.graphcompiler.schema.ReactFlowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class NodeEnricher {

    private final SimulationParameterRegistry parameterRegistry;

    public Map<String, Map<String, Object>> enrichNodes(InternalGraph graph) {
        Map<String, Map<String, Object>> enrichedNodes = new HashMap<>();

        for (Map.Entry<String, ReactFlowNode> entry : graph.getNodeMap().entrySet()) {
            String nodeId = entry.getKey();
            ReactFlowNode node = entry.getValue();

            if (node == null || node.getType() == null) {
                continue;
            }

            NodeType nodeType;
            try {
                nodeType = NodeType.fromValue(node.getType());
            } catch (Exception e) {
                log.warn("Unknown node type '{}' for node '{}', skipping enrichment", node.getType(), nodeId);
                continue;
            }

            SimulationParameters params = parameterRegistry.getParameters(nodeType);
            Map<String, Object> metadata = new HashMap<>();

            metadata.put("capacity", new HashMap<>(params.getCapacity()));
            metadata.put("latency", new HashMap<>(params.getLatency()));
            metadata.put("throughput", new HashMap<>(params.getThroughput()));
            metadata.put("properties", new HashMap<>(params.getProperties()));
            metadata.put("failureModes", new ArrayList<>(params.getFailureModes()));
            metadata.put("recoveryTimeMs", params.getRecoveryTimeMs());

            if (node.getData() != null && node.getData().getConfig() != null) {
                metadata.put("userConfig", node.getData().getConfig());
            }

            enrichedNodes.put(nodeId, metadata);
        }

        log.debug("Enriched {} nodes with simulation metadata", enrichedNodes.size());
        return enrichedNodes;
    }
}
