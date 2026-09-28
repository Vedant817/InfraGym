package com.archforge.graphcompiler.compiler;

import com.archforge.graphcompiler.schema.ReactFlowEdge;
import com.archforge.graphcompiler.schema.ReactFlowNode;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

@Getter
@Builder
public class InternalGraph {
    private final Map<String, ReactFlowNode> nodeMap;
    private final Map<String, List<String>> adjacencyList;
    private final Map<String, List<String>> reverseAdjacencyList;
    private final List<ReactFlowEdge> edges;
    @Setter
    private List<String> topologicalOrder;
    @Setter
    private Map<String, GraphMetrics> nodeMetrics;

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
