package com.archforge.simulation.support;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.graph.CompiledEdge;
import com.archforge.contracts.graph.CompiledGraph;
import com.archforge.contracts.graph.CompiledNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Compiled-graph fixtures with the values the graph compiler's
 * {@code SimulationParameterRegistry} actually publishes.
 *
 * <p><b>Why the numbers are hard-coded here rather than mocked.</b> A test that
 * invents its own capacity figures proves nothing about the pipeline: the original
 * engine's tests passed against a graph where every node reported a plausible number,
 * while in production a cache was rated at 1 rps because its rate lives in
 * {@code throughput} and the lookup only read {@code capacity}. These fixtures copy
 * the registry's published values exactly, so a change to what the compiler emits
 * shows up as a failing test here rather than as a wrong number in production.
 *
 * <p>Everything is overridable per node so a test can express "a database with 200
 * IOPS" without rebuilding the rest of the design.
 */
public final class TestGraphs {

    private TestGraphs() {
    }

    // --- registry values, verbatim from SimulationParameterRegistry -------------

    public static CompiledNode service(String id) {
        return builder(id, NodeType.SERVICE, 0)
                .capacity("requestsPerSecond", 1000).capacity("cpuCores", 2).capacity("memoryMb", 512)
                .latency("p50Ms", 20).latency("p95Ms", 100).latency("p99Ms", 200)
                .throughput("requestsPerSecond", 1000)
                .property("errorRate", 0.001)
                .build();
    }

    /**
     * A cache. Note {@code operationsPerSecond} lives in {@code throughput}, not
     * {@code capacity} — the omission that made a 50 000 ops/second cache simulate as
     * 1 rps.
     */
    public static CompiledNode cache(String id) {
        return builder(id, NodeType.CACHE, 0)
                .capacity("entries", 10000).capacity("memoryMb", 256)
                .latency("p50Ms", 1).latency("p95Ms", 5).latency("p99Ms", 10)
                .throughput("operationsPerSecond", 50000)
                .property("hitRate", 0.8)
                .property("evictionPolicy", "LRU")
                .property("ttlSeconds", 300)
                .build();
    }

    public static CompiledNode database(String id) {
        return builder(id, NodeType.DATABASE, 0)
                .capacity("connections", 100).capacity("storageGb", 100).capacity("iops", 3000)
                .latency("readP50Ms", 5).latency("readP95Ms", 20)
                .latency("writeP50Ms", 10).latency("writeP95Ms", 50)
                .throughput("queriesPerSecond", 5000)
                .throughput("transactionsPerSecond", 1000)
                .property("replicationFactor", 1)
                .build();
    }

    public static CompiledNode queue(String id) {
        return builder(id, NodeType.QUEUE, 0)
                .capacity("partitions", 3).capacity("retentionMs", 604800000.0)
                .latency("p50Ms", 5).latency("p95Ms", 50).latency("p99Ms", 200)
                .throughput("messagesPerSecond", 10000)
                .property("minInSyncReplicas", 2)
                .build();
    }

    public static CompiledNode loadBalancer(String id) {
        return builder(id, NodeType.LOAD_BALANCER, 0)
                .capacity("connections", 10000).capacity("requestsPerSecond", 50000)
                .latency("p50Ms", 1).latency("p95Ms", 5).latency("p99Ms", 15)
                .throughput("requestsPerSecond", 50000)
                .build();
    }

    public static CompiledNode worker(String id) {
        return builder(id, NodeType.WORKER, 0)
                .capacity("concurrency", 10).capacity("queueSize", 1000).capacity("memoryMb", 256)
                .latency("processingTimeMs", 100).latency("p95Ms", 500).latency("p99Ms", 1000)
                .throughput("tasksPerSecond", 100)
                .build();
    }

    public static CompiledNode external(String id) {
        return builder(id, NodeType.EXTERNAL, 0)
                .capacity("requestsPerSecond", 100)
                .latency("p50Ms", 100).latency("p95Ms", 500).latency("p99Ms", 2000)
                .throughput("requestsPerSecond", 100)
                .property("timeoutMs", 5000)
                .build();
    }

    /** A monitoring node. Its failure is not observable by a caller, so it is never a target. */
    public static CompiledNode monitoring(String id) {
        return builder(id, NodeType.MONITORING, 0)
                .capacity("metricsPerSecond", 1000)
                .latency("p50Ms", 50).latency("p95Ms", 200).latency("p99Ms", 1000)
                .throughput("metricsPerSecond", 1000)
                .property("scrapeIntervalSeconds", 15)
                .build();
    }

    /** A component that declares nothing at all, to exercise the declared floors. */
    public static CompiledNode bare(String id, NodeType type) {
        return builder(id, type, 0).build();
    }

    // --- builder ----------------------------------------------------------------

    public static Builder builder(String id, NodeType type, int order) {
        return new Builder(id, type, order);
    }

    /** Fluent node builder; every declared value goes into the right slot. */
    public static final class Builder {
        private final String id;
        private final NodeType type;
        private final int order;
        private final Map<String, Double> capacity = new LinkedHashMap<>();
        private final Map<String, Double> latency = new LinkedHashMap<>();
        private final Map<String, Double> throughput = new LinkedHashMap<>();
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final LinkedHashSet<String> dependencies = new LinkedHashSet<>();
        private final LinkedHashSet<String> dependents = new LinkedHashSet<>();
        private String label;
        private long recoveryTimeMs;

        private Builder(String id, NodeType type, int order) {
            this.id = id;
            this.type = type;
            this.order = order;
            this.label = id;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public Builder capacity(String key, double value) {
            capacity.put(key, value);
            return this;
        }

        /** Shorthand for {@code capacity("concurrency", n)}. */
        public Builder concurrency(double value) {
            return capacity("concurrency", value);
        }

        public Builder latency(String key, double value) {
            latency.put(key, value);
            return this;
        }

        public Builder throughput(String key, double value) {
            throughput.put(key, value);
            return this;
        }

        public Builder property(String key, Object value) {
            properties.put(key, value);
            return this;
        }

        /**
         * Declares an upstream dependency directly.
         *
         * <p>Only for a fixture that needs to disagree with the edge list — an
         * unreachable component, say. {@link #graph(String, List, List)} recomputes
         * both directions from the edges, so anything declared here is overwritten.
         */
        public Builder dependsOn(String upstreamId) {
            dependencies.add(upstreamId);
            return this;
        }

        public Builder recoveryTimeMs(long millis) {
            this.recoveryTimeMs = millis;
            return this;
        }

        public Builder dependentOf(String downstreamId) {
            dependents.add(downstreamId);
            return this;
        }

        public CompiledNode build() {
            Map<String, Object> metadata = new LinkedHashMap<>();
            // Matches NodeEnricher: the enrichment blob carries a "properties" bag.
            metadata.put("properties", new LinkedHashMap<>(properties));
            return CompiledNode.builder()
                    .id(id)
                    .type(type)
                    .label(label)
                    .dependencies(dependencies)
                    .dependents(dependents)
                    .simulationMetadata(metadata)
                    .capacity(capacity)
                    .latencyProfile(latency)
                    .throughput(throughput)
                    .recoveryTimeMs(recoveryTimeMs)
                    .topologicalOrder(order)
                    .build();
        }
    }

    /**
     * Assembles a graph, deriving both adjacency directions from the edge list.
     *
     * <p>Both directions, because the compiler derives both and they are not
     * interchangeable. {@code dependencies} is what a component must wait for;
     * {@code dependents} is the reverse index. Deriving them here rather than asking
     * the caller to supply both is deliberate: a fixture that hand-writes only one
     * direction produces a graph that cannot exist, and the simulator reads them
     * differently — arrivals come from incoming edges, failure blast radius from
     * {@code dependencies}. A fixture with {@code dependents} but empty
     * {@code dependencies} reports a blast radius of zero for a node that plainly has
     * one, and the test asserting on it passes for the wrong reason.
     */
    public static CompiledGraph graph(String name, List<CompiledNode> nodes, List<CompiledEdge> edges) {
        Map<String, LinkedHashSet<String>> upstream = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> downstream = new LinkedHashMap<>();
        for (CompiledNode node : nodes) {
            upstream.put(node.getId(), new LinkedHashSet<>());
            downstream.put(node.getId(), new LinkedHashSet<>());
        }
        for (CompiledEdge edge : edges) {
            downstream.computeIfAbsent(edge.getSource(), key -> new LinkedHashSet<>())
                    .add(edge.getTarget());
            upstream.computeIfAbsent(edge.getTarget(), key -> new LinkedHashSet<>())
                    .add(edge.getSource());
        }

        List<CompiledNode> wired = new ArrayList<>(nodes.size());
        for (CompiledNode node : nodes) {
            wired.add(CompiledNode.builder()
                    .id(node.getId())
                    .type(node.getType())
                    .label(node.getLabel())
                    .dependencies(upstream.getOrDefault(node.getId(), new LinkedHashSet<>()))
                    .dependents(downstream.getOrDefault(node.getId(), new LinkedHashSet<>()))
                    .simulationMetadata(node.getSimulationMetadata())
                    .capacity(node.getCapacity())
                    .latencyProfile(node.getLatencyProfile())
                    .throughput(node.getThroughput())
                    .failureModes(node.getFailureModes())
                    .recoveryTimeMs(node.getRecoveryTimeMs())
                    .topologicalOrder(node.getTopologicalOrder())
                    .build());
        }

        return CompiledGraph.builder()
                .name(name)
                .nodes(wired)
                .edges(edges)
                .graphMetadata(Map.of("criticalPathLength", (long) edges.size()))
                .compiledAt(java.time.Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    public static CompiledEdge edge(String source, String target) {
        return CompiledEdge.builder()
                .source(source)
                .target(target)
                .latencyMs(1.0)
                .bandwidthMbps(1000.0)
                .build();
    }

    public static CompiledEdge edge(String source, String target, double latencyMs) {
        return CompiledEdge.builder()
                .source(source)
                .target(target)
                .latencyMs(latencyMs)
                .bandwidthMbps(1000.0)
                .build();
    }

    // --- shapes -----------------------------------------------------------------

    /**
     * {@code lb -> api -> cache -> db}, the read-through chain.
     *
     * <p>Chain, not fan-out, and that matters: a cache's misses reach the store only if
     * the cache is drawn in front of it. This is the shape in which a cache outage can
     * visibly become a database outage, and it is the fixture both cache tests use.
     */
    public static CompiledGraph readThroughCache() {
        return graph("Read-through cache",
                List.of(loadBalancer("lb"), service("api"), cache("cache"), database("db")),
                List.of(edge("lb", "api"), edge("api", "cache"), edge("cache", "db")));
    }

    /** {@code lb -> api -> db}: no cache, so a baseline to compare the cache against. */
    public static CompiledGraph withoutCache() {
        return graph("No cache",
                List.of(loadBalancer("lb"), service("api"), database("db")),
                List.of(edge("lb", "api"), edge("api", "db")));
    }

    /** {@code lb -> api -> queue -> worker}: asynchronous, so a burst becomes lag. */
    public static CompiledGraph queued() {
        return graph("Queued pipeline",
                List.of(loadBalancer("lb"), service("api"), queue("q"), worker("w")),
                List.of(edge("lb", "api"), edge("api", "q"), edge("q", "w")));
    }

    /** {@code lb -> api -> db -> external}, deep enough to accumulate path latency. */
    public static CompiledGraph deepChain() {
        return graph("Deep chain",
                List.of(loadBalancer("lb"), service("api"), service("mid"),
                        database("db"), external("ext")),
                List.of(edge("lb", "api"), edge("api", "mid"), edge("mid", "db"),
                        edge("db", "ext")));
    }

    /** A lone component with no edges: exercises the entry-point and sink paths. */
    public static CompiledGraph singleNode() {
        return graph("Single service", List.of(service("only")), List.of());
    }

    /** A component that declares neither throughput nor latency. */
    public static CompiledGraph undeclared() {
        return graph("Undeclared characteristics",
                List.of(bare("lb", NodeType.LOAD_BALANCER), bare("api", NodeType.SERVICE)),
                List.of(edge("lb", "api")));
    }
}

