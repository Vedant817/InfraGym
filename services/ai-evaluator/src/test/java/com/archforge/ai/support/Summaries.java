package com.archforge.ai.support;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.event.ArchitectureEvaluationEvent;
import com.archforge.contracts.event.SimulationLifecycleEvent;
import com.archforge.contracts.event.SimulationSummaryEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds summaries for tests, without one builder per test class.
 *
 * <p>Most of these tests are about a specific arithmetic interaction, and a fixture that
 * makes every field explicit means a test states the situation it is describing rather than
 * inheriting a default that quietly matches. The accounting identity is provided as three
 * inputs ({@code offered}, {@code failed}, {@code queued}) and {@code served} is derived, so
 * a test cannot accidentally build an internally inconsistent summary unless it means to.
 */
public final class Summaries {

    public static final String SIM = "sim-1";
    public static final String SUB = "sub-1";
    public static final String CORR = "corr-1";

    private Summaries() {
    }

    /** A component's measured figures. */
    public record Node(String id, String type, double p50, double p95, double p99,
                       double maxLatency, double avgErrorRate, double peakErrorRate,
                       double peakRps, double peakUtilization, double avgCacheHitRate,
                       long peakQueueDepth, long samples) {

        /** A component that is comfortably inside every threshold. */
        public static Node healthy(String id, String type, double p50) {
            return new Node(id, type, p50, p50 * 1.2, p50 * 1.4, p50 * 1.5,
                    0, 0, 100, 0.4, -1, 0, 60);
        }

        /** A component with a specific P99 and nothing else interesting. */
        public static Node withP99(String id, double p99) {
            return new Node(id, NodeType.SERVICE.getValue(), p99 / 3, p99 / 1.5, p99, p99,
                    0, 0, 100, 0.4, -1, 0, 60);
        }

        public SimulationSummaryEvent.NodeSummary toSummary() {
            return new SimulationSummaryEvent.NodeSummary(id, type, id,
                    p50, p95, p99, maxLatency, avgErrorRate, peakErrorRate,
                    peakRps, peakRps / 2, peakUtilization, avgCacheHitRate,
                    peakQueueDepth, false, peakUtilization >= 1.0, samples);
        }
    }

    /** A summary being assembled. */
    public static final class Builder {

        private String simulationId = SIM;
        private String scenario = "flash_sale";
        private final List<Node> nodes = new ArrayList<>();
        private final List<SimulationSummaryEvent.FailureRecord> failures = new ArrayList<>();
        private List<String> warnings = List.of();
        private SimulationLifecycleEvent.ArchitectureDigest architecture;
        private double offered;
        private double errors;
        private double queued;
        private long tickCount = 60;
        private long totalTicks = 60;
        private double peakThroughput = 100;

        public Builder simulationId(String simulationId) {
            this.simulationId = simulationId;
            return this;
        }

        public Builder scenario(String scenario) {
            this.scenario = scenario;
            return this;
        }

        public Builder node(Node node) {
            nodes.add(node);
            return this;
        }

        public Builder nodes(Node... added) {
            nodes.addAll(List.of(added));
            return this;
        }

        public Builder failure(SimulationSummaryEvent.FailureRecord failure) {
            failures.add(failure);
            return this;
        }

        public Builder warnings(List<String> warnings) {
            this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
            return this;
        }

        public Builder architecture(SimulationLifecycleEvent.ArchitectureDigest architecture) {
            this.architecture = architecture;
            return this;
        }

        public Builder offered(double offered) {
            this.offered = offered;
            return this;
        }

        public Builder errors(double errors) {
            this.errors = errors;
            return this;
        }

        public Builder queued(double queued) {
            this.queued = queued;
            return this;
        }

        public Builder ticks(long executed, long requested) {
            this.tickCount = executed;
            this.totalTicks = requested;
            return this;
        }

        public Builder peakThroughput(double peakThroughput) {
            this.peakThroughput = peakThroughput;
            return this;
        }

        public SimulationSummaryEvent build() {
            double served = Math.max(0, offered - errors - queued);
            SimulationSummaryEvent.Totals totals = new SimulationSummaryEvent.Totals(
                    nodes.stream().mapToDouble(Node::p99).max().orElse(0), 0, 0,
                    0, 0, peakThroughput,
                    tickCount > 0 ? offered / tickCount : 0,
                    offered, errors, queued,
                    (int) nodes.stream().filter(n -> n.peakUtilization >= 1.0).count(), 0);
            return new SimulationSummaryEvent(
                    simulationId, SUB, CORR, scenario,
                    architecture, failures,
                    nodes.stream().map(Node::toSummary).toList(),
                    totals, List.of(), Map.of(),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T00:01:00Z"),
                    tickCount, totalTicks, 1000L, warnings);
        }
    }

    public static Builder summary() {
        return new Builder();
    }

    /**
     * A straight-line graph, with each component's type stated at the call site.
     *
     * <p>Types are explicit rather than derived from position. A helper that typed the last
     * node as a database regardless of length quietly turned a two-component chain into one
     * with a single point of failure, and the test asserting "a lone stateless component is
     * not a single point of failure" then failed for a reason that had nothing to do with
     * what it was testing.
     *
     * @param typeAndId entries as {@code "type:id"}, chained in the order given
     */
    public static SimulationLifecycleEvent.ArchitectureDigest graph(String... typeAndId) {
        List<SimulationLifecycleEvent.ArchitectureDigest.NodeDigest> nodes = new ArrayList<>();
        List<SimulationLifecycleEvent.ArchitectureDigest.EdgeDigest> edges = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (String entry : typeAndId) {
            int colon = entry.indexOf(':');
            String type = entry.substring(0, colon);
            String id = entry.substring(colon + 1);
            nodes.add(new SimulationLifecycleEvent.ArchitectureDigest.NodeDigest(id, type, id));
            ids.add(id);
            if (ids.size() > 1) {
                edges.add(new SimulationLifecycleEvent.ArchitectureDigest.EdgeDigest(
                        "e" + ids.size(), ids.get(ids.size() - 2), id, 1.0, 1000));
            }
        }
        return new SimulationLifecycleEvent.ArchitectureDigest(
                "test design", nodes, edges, Map.of(), List.of());
    }

    /** A graph with no component types that hold state. */
    public static SimulationLifecycleEvent.ArchitectureDigest statelessGraph(String... ids) {
        String[] entries = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            entries[i] = (i == 0 ? NodeType.LOAD_BALANCER.getValue()
                    : NodeType.SERVICE.getValue()) + ":" + ids[i];
        }
        return graph(entries);
    }

    public static ArchitectureEvaluationEvent.Finding finding(String nodeId, String metric) {
        return new ArchitectureEvaluationEvent.Finding("f-" + nodeId + "-" + metric,
                ArchitectureEvaluationEvent.Severity.HIGH, "latency", "title", "detail",
                "evidence", "recommendation", List.of(nodeId));
    }
}