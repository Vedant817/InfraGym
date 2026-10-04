package com.archforge.graphcompiler.compiler;

import com.archforge.contracts.canvas.CanvasEdge;
import com.archforge.contracts.canvas.CanvasNode;
import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.graph.CompiledEdge;
import com.archforge.contracts.graph.CompiledGraph;
import com.archforge.contracts.graph.CompiledNode;
import com.archforge.graphcompiler.enrichment.GraphEnrichmentPipeline;
import com.archforge.graphcompiler.exception.CompilationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Produces the {@link CompiledGraph} handed to the simulation engine.
 *
 * <p>This is the last stage before simulation, so it is where the compiler decides
 * what the engine is allowed to assume. Three decisions are worth stating:
 *
 * <ul>
 *   <li><b>An unrecognised node type becomes a compile error, not a default.</b> The
 *       previous version silently substituted {@code SERVICE}, producing a node whose
 *       declared type said "service" but which carried no capacity, no latency profile
 *       and no failure modes — because enrichment had skipped it. The simulator then
 *       received a component it could not model and invented numbers for it. A typo in
 *       the canvas now fails loudly.</li>
 *   <li><b>Latency and throughput are first-class fields.</b> The old
 *       {@code CompiledNode} had a {@code capacity} map but no latency map, so the
 *       engine had to dig through a nested {@code simulationMetadata} blob and fall
 *       back to a hard-coded 20 ms when a key was missing — which it always was, for
 *       databases and workers, whose latency keys are named {@code readP50Ms} and
 *       {@code processingTimeMs}. Every database in every simulation reported 20 ms.</li>
 *   <li><b>Edge weights are derived, and edge latency is additive downstream.</b>
 *       Bandwidth and propagation delay come from the target node's characteristics,
 *       so the engine adds real network cost to end-to-end latency instead of
 *       pretending the network is free.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DagCompiler {

    private final GraphEnrichmentPipeline enrichmentPipeline;

    public CompiledGraph compile(InternalGraph graph) {
        Objects.requireNonNull(graph, "graph must not be null");
        if (graph.getTopologicalOrder() == null) {
            throw new CompilationException("Graph has not been topologically sorted");
        }

        log.debug("Starting DAG compilation");

        GraphEnrichmentPipeline.EnrichedGraph enriched = enrichmentPipeline.enrich(graph);
        Map<String, Map<String, Object>> enrichedNodes = enriched.enrichedNodes();
        Map<String, Object> graphMetadata = new LinkedHashMap<>(enriched.graphMetadata());

        List<CompiledNode> compiledNodes = compileNodes(graph, enrichedNodes);
        List<CompiledEdge> compiledEdges = compileEdges(graph);

        // Rejected edges are a user-visible discrepancy between the canvas and the DAG.
        // Fold them into warnings so they travel with the graph instead of vanishing.
        List<String> warnings = new ArrayList<>(graph.rejectedEdgesOrEmpty().stream()
                .map(rejected -> "Edge " + rejected.label() + " was not compiled: " + rejected.detail())
                .toList());

        CompiledGraph compiledGraph = CompiledGraph.builder()
                .nodes(compiledNodes)
                .edges(compiledEdges)
                .graphMetadata(graphMetadata)
                .name(graphName(enrichedNodes, graph))
                .description("DAG compiled from a React Flow topology")
                .warnings(warnings)
                .build();

        log.debug("Compiled {} node(s) and {} edge(s)", compiledNodes.size(), compiledEdges.size());
        return compiledGraph;
    }

    private List<CompiledNode> compileNodes(InternalGraph graph, Map<String, Map<String, Object>> enrichedNodes) {
        List<CompiledNode> compiledNodes = new ArrayList<>();
        List<String> topoOrder = graph.getTopologicalOrder();

        Map<String, Set<String>> upstreamDeps = new LinkedHashMap<>();
        Map<String, Set<String>> downstreamDeps = new LinkedHashMap<>();
        Map<String, InternalGraph.GraphMetrics> metrics =
                graph.getNodeMetrics() == null ? Map.<String, InternalGraph.GraphMetrics>of() : graph.getNodeMetrics();

        for (int i = 0; i < topoOrder.size(); i++) {
            String nodeId = topoOrder.get(i);

            CanvasNode node = graph.getNodeMap().get(nodeId);
            if (node == null) {
                throw new CompilationException("Node '" + nodeId + "' is in the topological order "
                        + "but absent from the node map");
            }

            NodeType nodeType = NodeType.findByValue(node.getType());
            if (nodeType == null) {
                // SchemaValidator rejects unknown types on every public path. Reaching
                // here means DagCompiler was called directly, and defaulting to SERVICE
                // would ship an unmodellable node to the simulator.
                throw new CompilationException("Node '" + nodeId + "' has unknown type '"
                        + node.getType() + "' and cannot be compiled");
            }

            Map<String, Object> metadata = enrichedNodes.getOrDefault(nodeId, Map.of());
            InternalGraph.GraphMetrics nodeMetrics = metrics.get(nodeId);

            compiledNodes.add(CompiledNode.builder()
                    .id(nodeId)
                    .type(nodeType)
                    .label(node.label())
                    .dependencies(toSet(graph.getReverseAdjacencyList().get(nodeId)))
                    .dependents(toSet(graph.getAdjacencyList().get(nodeId)))
                    .simulationMetadata(new LinkedHashMap<>(metadata))
                    .capacity(toDoubleMap(metadata.get("capacity")))
                    .latencyProfile(toDoubleMap(metadata.get("latency")))
                    .throughput(toDoubleMap(metadata.get("throughput")))
                    .failureModes(toStringSet(metadata.get("failureModes")))
                    .recoveryTimeMs(toLong(metadata.get("recoveryTimeMs")))
                    .topologicalOrder(i)
                    .build());
        }

        return compiledNodes;
    }

    private List<CompiledEdge> compileEdges(InternalGraph graph) {
        List<CompiledEdge> compiledEdges = new ArrayList<>(graph.edgeCount());

        for (CanvasEdge edge : graph.getEdges()) {
            compiledEdges.add(CompiledEdge.builder()
                    .id(edge.getId())
                    .source(edge.getSource())
                    .target(edge.getTarget())
                    .label(edge.getLabel())
                    .protocol(inferProtocol(edge, graph))
                    .bandwidthMbps(inferBandwidth(edge, graph))
                    .latencyMs(inferLatency(edge, graph))
                    .build());
        }

        return compiledEdges;
    }

    /**
     * Protocol hint derived from the endpoints.
     *
     * <p>Advisory only — the simulator does not branch on it — but it is what the
     * frontend renders on the edge and what the evaluator reads to describe the design.
     */
    private String inferProtocol(CanvasEdge edge, InternalGraph graph) {
        NodeType sourceType = typeOf(graph, edge.getSource());
        NodeType targetType = typeOf(graph, edge.getTarget());

        if (targetType == null || sourceType == null) {
            return "unknown";
        }
        if (targetType == NodeType.QUEUE) {
            return "async";
        }
        if (sourceType == NodeType.EXTERNAL || targetType == NodeType.EXTERNAL) {
            return "https";
        }
        if (targetType == NodeType.CACHE) {
            return "cache-protocol";
        }
        if (targetType == NodeType.DATABASE) {
            return "sql";
        }
        if (sourceType == NodeType.WORKER || sourceType == NodeType.FUNCTION) {
            return "grpc";
        }
        if (sourceType == NodeType.CDN || targetType == NodeType.CDN) {
            return "https";
        }
        return "http";
    }

    /**
     * Link capacity in Mbps.
     *
     * <p>Storage and messaging links are the constrained ones, so they are modelled
     * narrower than compute-to-compute hops. The engine converts this into a byte-rate
     * ceiling and adds queuing delay when a link saturates, which is how a narrow
     * database link becomes an observable bottleneck instead of a footnote.
     */
    private double inferBandwidth(CanvasEdge edge, InternalGraph graph) {
        NodeType targetType = typeOf(graph, edge.getTarget());
        if (targetType == null) {
            return CompiledEdge.DEFAULT_BANDWIDTH_MBPS;
        }
        return switch (targetType) {
            case CDN -> 10_000;
            case DATABASE -> 1_000;
            case STORAGE -> 1_000;
            case QUEUE -> 5_000;
            case CACHE -> 5_000;
            case EXTERNAL -> 100;
            default -> 1_000;
        };
    }

    /**
     * Propagation delay in milliseconds.
     *
     * <p>These are wire-level costs, not service time, and the engine adds them to the
     * latency of whatever traverses the edge. The previous values started at 20 ms for
     * an ordinary service-to-service hop, which dominated the 20 ms service time and
     * made every design look network-bound.
     */
    private double inferLatency(CanvasEdge edge, InternalGraph graph) {
        NodeType targetType = typeOf(graph, edge.getTarget());
        if (targetType == null) {
            return CompiledEdge.DEFAULT_LATENCY_MS;
        }
        return switch (targetType) {
            case CACHE -> 0.5;
            case DATABASE -> 2.0;
            case QUEUE -> 1.0;
            case EXTERNAL -> 40.0;
            case CDN -> 15.0;
            default -> CompiledEdge.DEFAULT_LATENCY_MS;
        };
    }

    private NodeType typeOf(InternalGraph graph, String nodeId) {
        CanvasNode node = graph.getNodeMap().get(nodeId);
        return node == null ? null : NodeType.findByValue(node.getType());
    }

    /**
     * A human-readable graph name, taken from the first node's label when the
     * topology does not name the design itself.
     */
    private String graphName(Map<String, Map<String, Object>> enrichedNodes, InternalGraph graph) {
        return graph.getNodeMap().values().stream()
                .filter(java.util.Objects::nonNull)
                .map(CanvasNode::label)
                .filter(label -> label != null && !label.isBlank())
                .findFirst()
                .orElse("Compiled architecture");
    }

    private Set<String> toSet(List<String> values) {
        return values == null ? Set.of() : new LinkedHashSet<>(values);
    }

    /**
     * Narrows an enrichment value to a numeric map.
     *
     * <p>The enrichment blob is built partly from user-supplied config, so every value
     * arrives as {@code Object}. A user who writes {@code "p99Ms": "fast"} gets a
     * silently-absent latency entry rather than a {@code ClassCastException} that
     * fails their entire submission.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Double> toDoubleMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Double> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null && value instanceof Number number) {
                result.put(key.toString(), number.doubleValue());
            }
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> toStringSet(Object raw) {
        if (!(raw instanceof Collection<?> collection)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        collection.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .forEach(result::add);
        return result;
    }

    private static long toLong(Object raw) {
        return raw instanceof Number number ? number.longValue() : 0L;
    }
}
