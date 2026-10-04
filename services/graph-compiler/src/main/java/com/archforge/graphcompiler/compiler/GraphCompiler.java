package com.archforge.graphcompiler.compiler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates graph construction, sorting and metrics.
 *
 * <p><b>Two pieces of dead work removed.</b> The previous version:
 * <ul>
 *   <li>injected {@link DependencyResolver} and never called it, and</li>
 *   <li>computed graph depth, breadth and the critical path, then only logged them —
 *       discarding the results, which {@code GraphMetadataGenerator} then recomputed
 *       from scratch moments later. Two full graph traversals for one set of numbers.</li>
 * </ul>
 * Depth and breadth now live on the graph, set once here and read once by the metadata
 * generator.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphCompiler {

    private final GraphBuilder graphBuilder;
    private final TopologicalSorter topologicalSorter;
    private final GraphMetricsCalculator metricsCalculator;

    /**
     * @return the built, sorted and measured graph
     * @throws com.archforge.graphcompiler.exception.DAGCycleException if not acyclic
     */
    public InternalGraph compile(com.archforge.contracts.canvas.Topology topology) {
        InternalGraph graph = graphBuilder.build(topology);
        log.debug("Built graph: {} nodes, {} edges", graph.nodeCount(), graph.edgeCount());

        List<String> topoOrder = topologicalSorter.sort(graph);
        graph.setTopologicalOrder(topoOrder);
        log.debug("Topological sort completed over {} node(s)", topoOrder.size());

        graph.setNodeMetrics(metricsCalculator.calculate(graph));
        log.debug("Computed per-node metrics for {} node(s)", graph.getNodeMetrics().size());

        return graph;
    }
}
