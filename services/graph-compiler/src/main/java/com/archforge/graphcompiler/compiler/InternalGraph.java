package com.archforge.graphcompiler.compiler;

import com.archforge.contracts.canvas.CanvasEdge;
import com.archforge.contracts.canvas.CanvasNode;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * The compiler's working representation of a topology.
 *
 * <p>Mutable only in the two fields that are filled in progressively — the topological
 * order and the node metrics — because both are computed after construction and read
 * by later stages. Everything else is unmodifiable, and {@link GraphBuilder} wraps the
 * collections before handing them over.
 */
@Getter
@Builder
public class InternalGraph {

    private final Map<String, CanvasNode> nodeMap;
    private final Map<String, List<String>> adjacencyList;
    private final Map<String, List<String>> reverseAdjacencyList;
    private final List<CanvasEdge> edges;

    /**
     * Edges the builder refused to compile in.
     *
     * <p>Previously these were logged and discarded, so the compiled DAG could differ
     * from what the user drew with no way to find out why.
     */
    private final List<GraphBuilder.RejectedEdge> rejectedEdges;

    @Setter
    private List<String> topologicalOrder;

    @Setter
    private Map<String, GraphMetrics> nodeMetrics;

    public List<GraphBuilder.RejectedEdge> rejectedEdgesOrEmpty() {
        return rejectedEdges == null ? List.of() : rejectedEdges;
    }

    public int nodeCount() {
        return nodeMap == null ? 0 : nodeMap.size();
    }

    public int edgeCount() {
        return edges == null ? 0 : edges.size();
    }

    /** Per-node structural figures computed once during compilation. */
    @Getter
    @Builder
    public static class GraphMetrics {
        private final int depth;
        private final int fanIn;
        private final int fanOut;
        private final int upstreamCount;
        private final int downstreamCount;
    }
}
