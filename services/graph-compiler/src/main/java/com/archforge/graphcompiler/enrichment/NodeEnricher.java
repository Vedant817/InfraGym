package com.archforge.graphcompiler.enrichment;

import com.archforge.contracts.canvas.CanvasNode;
import com.archforge.contracts.canvas.NodeType;
import com.archforge.graphcompiler.compiler.InternalGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Attaches physical characteristics to each canvas node, so the simulator never has
 * to guess what a "service" or a "database" can do.
 *
 * <p>User configuration is merged here, and this is the only place in the compiler
 * that interprets it. {@link SimulationParameters} decides which bucket each key
 * belongs in; a user who sets {@code p99Ms: 40} gets a 40 ms tail rather than a
 * mystery entry in a properties bag that nothing reads.
 *
 * <p>Warnings are emitted rather than logged-and-dropped. A user who typos a
 * parameter gets told; a user whose override was classified somewhere they did not
 * expect gets told. Silent acceptance of a typo is how "I set 5000 rps and nothing
 * happened" happens.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeEnricher {

    private final SimulationParameterRegistry parameterRegistry;

    public Map<String, Map<String, Object>> enrichNodes(InternalGraph graph) {
        Map<String, Map<String, Object>> enrichedNodes = new HashMap<>();

        for (Map.Entry<String, CanvasNode> entry : graph.getNodeMap().entrySet()) {
            String nodeId = entry.getKey();
            CanvasNode node = entry.getValue();

            if (node == null) {
                continue;
            }

            NodeType nodeType = NodeType.findByValue(node.getType());
            if (nodeType == null) {
                // SchemaValidator rejects unknown types before we get here on every
                // public path. Log rather than skip silently so a direct DagCompiler
                // caller finds out.
                log.warn("Node '{}' has unknown type '{}'; it will receive no simulation parameters",
                        nodeId, node.getType());
                continue;
            }

            SimulationParameters defaults = parameterRegistry.getParameters(nodeType);
            SimulationParameters merged = defaults;

            Map<String, Object> userConfig = node.getData() == null ? null : node.getData().getConfig();
            if (userConfig != null && !userConfig.isEmpty()) {
                merged = defaults.mergeWith(userConfig);

                Object recoveryOverride = userConfig.get("recoveryTimeMs");
                if (recoveryOverride instanceof Number number) {
                    merged = merged.withRecoveryTimeMs(number.doubleValue());
                }

                List<String> unroutable = new ArrayList<>();
                for (String key : userConfig.keySet()) {
                    if ("recoveryTimeMs".equals(key)) {
                        continue;
                    }
                    if (SimulationParameters.slotFor(key) == null) {
                        unroutable.add(key);
                    }
                }
                if (!unroutable.isEmpty()) {
                    log.info("Node '{}' has config keys that are not recognised simulation "
                            + "parameters and will be carried through but not simulated: {}", nodeId, unroutable);
                }
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("capacity", new LinkedHashMap<>(merged.getCapacity()));
            metadata.put("latency", new LinkedHashMap<>(merged.getLatency()));
            metadata.put("throughput", new LinkedHashMap<>(merged.getThroughput()));
            metadata.put("properties", new LinkedHashMap<>(merged.getProperties()));
            metadata.put("failureModes", new ArrayList<>(merged.getFailureModes()));
            metadata.put("recoveryTimeMs", merged.getRecoveryTimeMs());

            if (userConfig != null && !userConfig.isEmpty()) {
                metadata.put("userConfig", new LinkedHashMap<>(userConfig));
            }

            enrichedNodes.put(nodeId, metadata);
        }

        log.debug("Enriched {} of {} nodes with simulation metadata",
                enrichedNodes.size(), graph.getNodeMap().size());
        return enrichedNodes;
    }
}
