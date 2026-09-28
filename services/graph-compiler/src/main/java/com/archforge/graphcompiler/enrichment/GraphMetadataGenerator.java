package com.archforge.graphcompiler.enrichment;

import com.archforge.graphcompiler.compiler.GraphMetricsCalculator;
import com.archforge.graphcompiler.compiler.InternalGraph;
import com.archforge.graphcompiler.exception.ValidationException;
import com.archforge.graphcompiler.schema.NodeType;
import com.archforge.graphcompiler.schema.ReactFlowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class GraphMetadataGenerator {

    private final GraphMetricsCalculator metricsCalculator;

    public Map<String, Object> generateMetadata(InternalGraph graph) {
        Map<String, Object> metadata = new HashMap<>();

        metadata.put("totalNodes", graph.getNodeMap().size());
        metadata.put("totalEdges", graph.getEdges().size());

        Map<String, Integer> nodeTypeCounts = new HashMap<>();
        for (ReactFlowNode node : graph.getNodeMap().values()) {
            if (node != null && node.getType() != null) {
                nodeTypeCounts.merge(node.getType(), 1, Integer::sum);
            }
        }
        metadata.put("nodeTypeCounts", nodeTypeCounts);

        int depth = metricsCalculator.calculateGraphDepth(graph);
        int breadth = metricsCalculator.calculateGraphBreadth(graph);
        List<String> criticalPath = metricsCalculator.findCriticalPath(graph);

        metadata.put("graphDepth", depth);
        metadata.put("graphBreadth", breadth);
        metadata.put("criticalPathLength", criticalPath.size());
        metadata.put("criticalPath", criticalPath);

        int computeNodes = 0;
        int storageNodes = 0;
        int messagingNodes = 0;
        int networkNodes = 0;

        for (ReactFlowNode node : graph.getNodeMap().values()) {
            if (node == null || node.getType() == null) {
                continue;
            }
            try {
                NodeType type = NodeType.fromValue(node.getType());
                if (type.isCompute()) computeNodes++;
                if (type.isStorage()) storageNodes++;
                if (type.isMessaging()) messagingNodes++;
                if (type.isNetwork() || type.isEdge()) networkNodes++;
            } catch (ValidationException e) {
                log.debug("Unknown node type '{}' during metadata generation", node.getType());
            }
        }

        metadata.put("computeNodeCount", computeNodes);
        metadata.put("storageNodeCount", storageNodes);
        metadata.put("messagingNodeCount", messagingNodes);
        metadata.put("networkNodeCount", networkNodes);

        double complexityScore = calculateComplexityScore(graph, depth, breadth);
        metadata.put("complexityScore", complexityScore);
        metadata.put("complexityRating", getComplexityRating(complexityScore));

        log.debug("Generated graph metadata: {} nodes, {} edges, depth {}, breadth {}",
                graph.getNodeMap().size(), graph.getEdges().size(), depth, breadth);

        return metadata;
    }

    private double calculateComplexityScore(InternalGraph graph, int depth, int breadth) {
        int nodes = graph.getNodeMap().size();
        int edges = graph.getEdges().size();

        if (nodes == 0) {
            return 0;
        }

        double edgeDensity = nodes > 1 ? (double) edges / ((long) nodes * (nodes - 1) / 2) : 0;
        double depthFactor = (double) depth / nodes;
        double breadthFactor = (double) breadth / nodes;

        double score = (edgeDensity * 0.3 + depthFactor * 0.2 + breadthFactor * 0.2 + Math.min(nodes / 100.0, 1.0) * 0.3) * 100;
        return Math.min(score, 100.0);
    }

    private String getComplexityRating(double score) {
        if (score < 20) return "LOW";
        if (score < 50) return "MEDIUM";
        if (score < 80) return "HIGH";
        return "VERY_HIGH";
    }
}
