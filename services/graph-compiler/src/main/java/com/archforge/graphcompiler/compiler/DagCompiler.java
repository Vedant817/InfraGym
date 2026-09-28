package com.archforge.graphcompiler.compiler;

import com.archforge.graphcompiler.enrichment.GraphEnrichmentPipeline;
import com.archforge.graphcompiler.exception.CompilationException;
import com.archforge.graphcompiler.exception.ValidationException;
import com.archforge.graphcompiler.model.CompiledEdge;
import com.archforge.graphcompiler.model.CompiledGraph;
import com.archforge.graphcompiler.model.CompiledNode;
import com.archforge.graphcompiler.schema.NodeType;
import com.archforge.graphcompiler.schema.ReactFlowEdge;
import com.archforge.graphcompiler.schema.ReactFlowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class DagCompiler {

    private final GraphEnrichmentPipeline enrichmentPipeline;

    public CompiledGraph compile(InternalGraph graph) {
        Objects.requireNonNull(graph, "graph must not be null");
        Objects.requireNonNull(graph.getTopologicalOrder(), "topological order must not be null");

        log.debug("Starting DAG compilation");

        GraphEnrichmentPipeline.EnrichedGraph enrichedGraph = enrichmentPipeline.enrich(graph);
        Map<String, Map<String, Object>> enrichedNodes = enrichedGraph.getEnrichedNodes();
        Map<String, Object> graphMetadata = enrichedGraph.getGraphMetadata();

        List<CompiledNode> compiledNodes = compileNodes(graph, enrichedNodes);
        List<CompiledEdge> compiledEdges = compileEdges(graph);

        CompiledGraph compiledGraph = CompiledGraph.builder()
                .nodes(compiledNodes)
                .edges(compiledEdges)
                .graphMetadata(graphMetadata)
                .name("Compiled Architecture")
                .description("DAG compiled from React Flow topology")
                .validationErrors(Collections.emptyList())
                .build();

        log.debug("DAG compilation completed: {} nodes, {} edges", compiledNodes.size(), compiledEdges.size());
        return compiledGraph;
    }

    private List<CompiledNode> compileNodes(InternalGraph graph, Map<String, Map<String, Object>> enrichedNodes) {
        List<CompiledNode> compiledNodes = new ArrayList<>();
        List<String> topoOrder = graph.getTopologicalOrder();

        Map<String, Set<String>> upstreamDeps = new HashMap<>();
        Map<String, Set<String>> downstreamDeps = new HashMap<>();

        for (String nodeId : graph.getNodeMap().keySet()) {
            Set<String> upstream = new HashSet<>();
            for (String pred : graph.getReverseAdjacencyList().getOrDefault(nodeId, Collections.emptyList())) {
                upstream.add(pred);
            }
            upstreamDeps.put(nodeId, upstream);

            Set<String> downstream = new HashSet<>();
            for (String succ : graph.getAdjacencyList().getOrDefault(nodeId, Collections.emptyList())) {
                downstream.add(succ);
            }
            downstreamDeps.put(nodeId, downstream);
        }

        for (int i = 0; i < topoOrder.size(); i++) {
            String nodeId = topoOrder.get(i);
            ReactFlowNode node = graph.getNodeMap().get(nodeId);

            if (node == null) {
                log.error("Node '{}' in topological order not found in node map", nodeId);
                throw new CompilationException("Node '" + nodeId + "' in topological order not found in node map");
            }

            NodeType nodeType;
            try {
                nodeType = NodeType.fromValue(node.getType());
            } catch (ValidationException e) {
                log.warn("Unknown node type '{}' for node '{}', defaulting to SERVICE", node.getType(), nodeId);
                nodeType = NodeType.SERVICE;
            }

            Map<String, Object> simulationMetadata = enrichedNodes.getOrDefault(nodeId, Collections.emptyMap());

            @SuppressWarnings("unchecked")
            Map<String, Double> capacity = (Map<String, Double>) simulationMetadata.getOrDefault("capacity", Collections.emptyMap());
            @SuppressWarnings("unchecked")
            List<String> failureModes = (List<String>) simulationMetadata.getOrDefault("failureModes", Collections.emptyList());
            double recoveryTimeMs = (double) simulationMetadata.getOrDefault("recoveryTimeMs", 0.0);

            compiledNodes.add(CompiledNode.builder()
                    .id(nodeId)
                    .type(nodeType)
                    .label(node.getData() != null ? node.getData().getLabel() : nodeId)
                    .dependencies(upstreamDeps.getOrDefault(nodeId, Collections.emptySet()))
                    .dependents(downstreamDeps.getOrDefault(nodeId, Collections.emptySet()))
                    .simulationMetadata(new HashMap<>(simulationMetadata))
                    .capacity(new HashMap<>(capacity))
                    .failureMode(failureModes.isEmpty() ? null : String.join(", ", failureModes))
                    .recoveryTimeMs((int) recoveryTimeMs)
                    .topologicalOrder(i)
                    .build());
        }

        return compiledNodes;
    }

    private List<CompiledEdge> compileEdges(InternalGraph graph) {
        List<CompiledEdge> compiledEdges = new ArrayList<>();
        Set<String> edgePairs = new HashSet<>();

        for (ReactFlowEdge edge : graph.getEdges()) {
            String direction = detectDirection(edge, graph, edgePairs);

            compiledEdges.add(CompiledEdge.builder()
                    .id(edge.getId())
                    .source(edge.getSource())
                    .target(edge.getTarget())
                    .label(edge.getLabel())
                    .protocol(inferProtocol(edge, graph))
                    .bandwidthMbps(inferBandwidth(edge, graph))
                    .latencyMs(inferLatency(edge, graph))
                    .direction(direction)
                    .build());
        }

        return compiledEdges;
    }

    private String detectDirection(ReactFlowEdge edge, InternalGraph graph, Set<String> edgePairs) {
        String forwardKey = edge.getSource() + "->" + edge.getTarget();
        String reverseKey = edge.getTarget() + "->" + edge.getSource();

        if (edgePairs.contains(reverseKey)) {
            return "bidirectional";
        }
        edgePairs.add(forwardKey);
        return "unidirectional";
    }

    private String inferProtocol(ReactFlowEdge edge, InternalGraph graph) {
        ReactFlowNode sourceNode = graph.getNodeMap().get(edge.getSource());
        ReactFlowNode targetNode = graph.getNodeMap().get(edge.getTarget());

        if (sourceNode == null || targetNode == null) {
            return "unknown";
        }

        NodeType sourceType = NodeType.SERVICE;
        NodeType targetType = NodeType.SERVICE;

        try {
            sourceType = NodeType.fromValue(sourceNode.getType());
        } catch (ValidationException ignored) {
        }

        try {
            targetType = NodeType.fromValue(targetNode.getType());
        } catch (ValidationException ignored) {
        }

        if (targetType == NodeType.QUEUE) {
            return "async";
        }
        if (targetType == NodeType.DATABASE || targetType == NodeType.CACHE) {
            return "tcp";
        }
        if (sourceType == NodeType.EXTERNAL || targetType == NodeType.EXTERNAL) {
            return "https";
        }
        if (sourceType == NodeType.WORKER || sourceType == NodeType.FUNCTION) {
            return "grpc";
        }

        return "http";
    }

    private Double inferBandwidth(ReactFlowEdge edge, InternalGraph graph) {
        ReactFlowNode sourceNode = graph.getNodeMap().get(edge.getSource());
        ReactFlowNode targetNode = graph.getNodeMap().get(edge.getTarget());

        if (sourceNode == null || targetNode == null) {
            return null;
        }

        try {
            NodeType targetType = NodeType.fromValue(targetNode.getType());
            if (targetType == NodeType.CDN) {
                return 10000.0;
            }
            if (targetType == NodeType.DATABASE) {
                return 1000.0;
            }
            if (targetType == NodeType.QUEUE) {
                return 5000.0;
            }
        } catch (ValidationException ignored) {
        }

        return 100.0;
    }

    private Double inferLatency(ReactFlowEdge edge, InternalGraph graph) {
        ReactFlowNode sourceNode = graph.getNodeMap().get(edge.getSource());
        ReactFlowNode targetNode = graph.getNodeMap().get(edge.getTarget());

        if (sourceNode == null || targetNode == null) {
            return null;
        }

        try {
            NodeType targetType = NodeType.fromValue(targetNode.getType());
            if (targetType == NodeType.CACHE) {
                return 1.0;
            }
            if (targetType == NodeType.DATABASE) {
                return 10.0;
            }
            if (targetType == NodeType.QUEUE) {
                return 5.0;
            }
            if (targetType == NodeType.EXTERNAL) {
                return 200.0;
            }
        } catch (ValidationException ignored) {
        }

        return 20.0;
    }
}
