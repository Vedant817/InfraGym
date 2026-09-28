package com.archforge.graphcompiler.compiler;

import com.archforge.graphcompiler.schema.ReactFlowEdge;
import com.archforge.graphcompiler.schema.ReactFlowNode;
import lombok.Builder;
import lombok.Data;

import java.util.*;

@Data
@Builder
public class InternalGraph {
    private Map<String, ReactFlowNode> nodeMap;
    private Map<String, List<String>> adjacencyList;
    private Map<String, List<String>> reverseAdjacencyList;
    private List<ReactFlowEdge> edges;
    private List<String> topologicalOrder;
    private Map<String, GraphMetrics> nodeMetrics;

    @Data
    @Builder
    public static class GraphMetrics {
        private int depth;
        private int fanIn;
        private int fanOut;
        private int upstreamCount;
        private int downstreamCount;
    }
}
