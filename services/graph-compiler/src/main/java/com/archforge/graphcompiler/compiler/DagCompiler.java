package com.archforge.graphcompiler.compiler;

import com.archforge.graphcompiler.enrichment.GraphEnrichmentPipeline;
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
                continue;
            }

            NodeType nodeType;
            try {
                nodeType = NodeType.fromValue(node.getType());
            } catch (ValidationException e) {
                log.warn("Unknown node type '{}' for node '{}', defaulting to SERVICE", node.getType(), nodeId);
                nodeType = NodeType.SERVICE;
            }

            Map<String, Object> simulationMetadata = enrichedNodes.getOrDefault(nodeId, new HashMap<>());

            compiledNodes.add(CompiledNode.builder()
                    .id(nodeId)
                    .type(nodeType)
                    .label(node.getData() != null ? node.getData().getLabel() : nodeId)
                    .dependencies(upstreamDeps.getOrDefault(nodeId, Collections.emptySet()))
                    .dependents(downstreamDeps.getOrDefault(nodeId, Collections.emptySet()))
                    .simulationMetadata(simulationMetadata)
                    .topologicalOrder(i)
                    .build());
        }

        return compiledNodes;
    }

    private List<CompiledEdge> compileEdges(InternalGraph graph) {
        List<CompiledEdge> compiledEdges = new ArrayList<>();

        for (ReactFlowEdge edge : graph.getEdges()) {
            compiledEdges.add(CompiledEdge.builder()
                    .id(edge.getId())
                    .source(edge.getSource())
                    .target(edge.getTarget())
                    .label(edge.getLabel())
                    .protocol(inferProtocol(edge, graph))
                    .bandwidthMbps(null)
                    .latencyMs(null)
                    .direction("unidirectional")
                    .build());
        }

        return compiledEdges;
    }

    private String inferProtocol(ReactFlowEdge edge, InternalGraph graph) {
        ReactFlowNode sourceNode = graph.getNodeMap().get(edge.getSource());
        ReactFlowNode targetNode = graph.getNodeMap().get(edge.getTarget());

        if (sourceNode == null || targetNode == null) {
            return "unknown";
        }

        String sourceType = sourceNode.getType();
        String targetType = targetNode.getType();

        if ("queue".equals(targetType)) {
            return "async";
        }
        if ("database".equals(targetType)) {
            return "tcp";
        }
        if ("cache".equals(targetType)) {
            return "tcp";
        }
        if ("external".equals(sourceType) || "external".equals(targetType)) {
            return "https";
        }

        return "http";
    }
}
