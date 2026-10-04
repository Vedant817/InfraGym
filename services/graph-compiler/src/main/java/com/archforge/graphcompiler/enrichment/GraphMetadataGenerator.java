package com.archforge.graphcompiler.enrichment;

import com.archforge.contracts.canvas.CanvasNode;
import com.archforge.contracts.canvas.NodeCategory;
import com.archforge.contracts.canvas.NodeType;
import com.archforge.graphcompiler.compiler.GraphMetricsCalculator;
import com.archforge.graphcompiler.compiler.InternalGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Produces the graph-level summary published alongside every compiled graph.
 *
 * <p>This is the metadata the frontend draws the architecture summary from and the
 * AI evaluator reads to understand the design's shape, so it is a wire contract, not
 * debug output.
 *
 * <p>Depth, breadth and the critical path are read from the values already computed on
 * the graph. The previous implementation recomputed all three here after
 * {@code GraphCompiler} had computed and discarded the same three — two traversals of
 * the whole graph for one set of numbers.
 *
 * <p>Node-type counts use a {@link TreeMap} so the emitted JSON has a stable key order
 * across runs. The previous version used a {@code HashMap}, so the same design
 * serialised its type census in a different order on each run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphMetadataGenerator {

    private final GraphMetricsCalculator metricsCalculator;

    public Map<String, Object> generateMetadata(InternalGraph graph) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        metadata.put("totalNodes", graph.nodeCount());
        metadata.put("totalEdges", graph.edgeCount());

        int rejectedEdgeCount = graph.rejectedEdgesOrEmpty().size();
        if (rejectedEdgeCount > 0) {
            // Published rather than logged only, so a user whose compiled DAG differs
            // from their canvas can find out which connection was dropped.
            metadata.put("rejectedEdgeCount", rejectedEdgeCount);
            metadata.put("rejectedEdges", graph.rejectedEdgesOrEmpty().stream()
                    .map(GraphBuilderRejectedEdgeView::new)
                    .toList());
        }

        Map<String, Integer> nodeTypeCounts = new TreeMap<>();
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        for (NodeCategory category : NodeCategory.values()) {
            categoryCounts.put(category.name(), 0);
        }

        int untypedNodes = 0;
        for (CanvasNode node : graph.getNodeMap().values()) {
            NodeType type = node == null ? null : NodeType.findByValue(node.getType());
            if (type == null) {
                untypedNodes++;
                continue;
            }
            nodeTypeCounts.merge(type.getValue(), 1, Integer::sum);
            categoryCounts.merge(type.getCategory().name(), 1, Integer::sum);
        }
        metadata.put("nodeTypeCounts", nodeTypeCounts);
        metadata.put("categoryCounts", categoryCounts);
        if (untypedNodes > 0) {
            metadata.put("untypedNodeCount", untypedNodes);
        }

        int depth = metricsCalculator.calculateGraphDepth(graph);
        int breadth = metricsCalculator.calculateGraphBreadth(graph);
        var criticalPath = metricsCalculator.findCriticalPath(graph);

        metadata.put("graphDepth", depth);
        metadata.put("graphBreadth", breadth);
        metadata.put("criticalPath", criticalPath);

        int computeNodes = categoryCounts.getOrDefault(NodeCategory.COMPUTE.name(), 0);
        int storageNodes = categoryCounts.getOrDefault(NodeCategory.STORAGE.name(), 0);
        int messagingNodes = categoryCounts.getOrDefault(NodeCategory.MESSAGING.name(), 0);
        int networkNodes = categoryCounts.getOrDefault(NodeCategory.NETWORK.name(), 0);
        int edgeNodes = categoryCounts.getOrDefault(NodeCategory.EDGE.name(), 0);

        metadata.put("computeNodeCount", computeNodes);
        metadata.put("storageNodeCount", storageNodes);
        metadata.put("messagingNodeCount", messagingNodes);
        metadata.put("networkNodeCount", networkNodes);
        metadata.put("edgeNodeCount", edgeNodes);

        // Fan-in concentration: the highest out-degree in the graph. A single node
        // fanning out to many is a coupling smell the evaluator can be told about
        // without re-deriving it.
        int maxFanOut = graph.getNodeMap().keySet().stream()
                .mapToInt(id -> graph.getAdjacencyList().getOrDefault(id, java.util.List.of()).size())
                .max()
                .orElse(0);
        int maxFanIn = graph.getNodeMap().keySet().stream()
                .mapToInt(id -> graph.getReverseAdjacencyList().getOrDefault(id, java.util.List.of()).size())
                .max()
                .orElse(0);
        metadata.put("maxFanOut", maxFanOut);
        metadata.put("maxFanIn", maxFanIn);

        boolean hasEntryPoint = graph.getNodeMap().values().stream()
                .map(node -> node == null ? null : NodeType.findByValue(node.getType()))
                .filter(java.util.Objects::nonNull)
                .anyMatch(NodeType::canBeEntryPoint);

        metadata.put("hasEntryPoint", hasEntryPoint);
        metadata.put("isAsync", messagingNodes > 0);

        double complexityScore = calculateComplexityScore(graph, depth, breadth, criticalPath.size());
        metadata.put("complexityScore", round(complexityScore));
        metadata.put("complexityRating", complexityRating(complexityScore));

        log.debug("Generated metadata for {} nodes: depth={}, breadth={}, criticalPath={}",
                graph.nodeCount(), depth, breadth, criticalPath.size());

        return metadata;
    }

    /**
     * Heuristic 0-100 complexity score.
     *
     * <p>Weighted across four observable properties, each normalised to [0,1]:
     * edge density, depth, breadth and critical-path length. Exposed as
     * {@code complexityScore} with the inputs alongside it, so the number is
     * interpretable rather than a bare figure nobody can argue with.
     */
    private double calculateComplexityScore(InternalGraph graph, int depth, int breadth, int criticalPathLength) {
        int nodes = graph.nodeCount();
        if (nodes == 0) {
            return 0;
        }

        double edgeDensity = nodes > 1
                ? (double) graph.edgeCount() / ((double) nodes * (nodes - 1) / 2)
                : 0;
        double depthFactor = (double) depth / nodes;
        double breadthFactor = (double) breadth / nodes;
        double pathFactor = (double) criticalPathLength / nodes;
        double sizeFactor = Math.min(nodes / 100.0, 1.0);

        double score = (edgeDensity * 0.25
                + depthFactor * 0.20
                + breadthFactor * 0.15
                + pathFactor * 0.20
                + sizeFactor * 0.20) * 100;

        return Math.min(score, 100.0);
    }

    private String complexityRating(double score) {
        if (score < 20) {
            return "LOW";
        }
        if (score < 50) {
            return "MEDIUM";
        }
        if (score < 80) {
            return "HIGH";
        }
        return "VERY_HIGH";
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Serialisable view of a dropped edge, for the compiled graph's metadata. */
    public record GraphBuilderRejectedEdgeView(String label, String reason, String detail) {
        public GraphBuilderRejectedEdgeView(com.archforge.graphcompiler.compiler.GraphBuilder.RejectedEdge edge) {
            this(edge.label(), edge.reason(), edge.detail());
        }
    }
}
