package com.archforge.graphcompiler.compiler;

import com.archforge.graphcompiler.schema.ReactFlowTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GraphCompiler {

    private final GraphBuilder graphBuilder;
    private final TopologicalSorter topologicalSorter;
    private final GraphMetricsCalculator metricsCalculator;
    private final DependencyResolver dependencyResolver;

    public InternalGraph compile(ReactFlowTopology topology) {
        log.debug("Starting graph compilation");

        InternalGraph graph = graphBuilder.build(topology);
        log.debug("Graph built: {} nodes, {} edges", graph.getNodeMap().size(), graph.getEdges().size());

        List<String> topoOrder = topologicalSorter.sort(graph);
        graph.setTopologicalOrder(topoOrder);
        log.debug("Topological sort completed: {} nodes", topoOrder.size());

        Map<String, InternalGraph.GraphMetrics> metrics = metricsCalculator.calculate(graph);
        graph.setNodeMetrics(metrics);
        log.debug("Metrics calculated for {} nodes", metrics.size());

        int depth = metricsCalculator.calculateGraphDepth(graph);
        int breadth = metricsCalculator.calculateGraphBreadth(graph);
        List<String> criticalPath = metricsCalculator.findCriticalPath(graph);

        log.debug("Graph metrics - depth: {}, breadth: {}, critical path length: {}", depth, breadth, criticalPath.size());

        return graph;
    }
}
