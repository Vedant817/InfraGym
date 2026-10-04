package com.archforge.contracts.event;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.lifecycle.ScenarioType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The complete, final picture of one simulation run. Produced by the telemetry
 * aggregator on {@code aggregated.summary} when it observes the matching
 * {@link SimulationLifecycleEvent} terminal transition, and consumed by the AI
 * evaluator and the design service.
 *
 * <p>This is the contract that lets the evaluator be grounded in measurements
 * instead of guesswork. It carries three things together, which is the whole
 * premise of the product:
 * <ol>
 *   <li>what the user designed ({@code architecture}),</li>
 *   <li>what was thrown at it ({@code scenario}, {@code failures}),</li>
 *   <li>what actually happened ({@code nodeSummaries}, {@code totals},
 *       {@code windowAggregates}).</li>
 * </ol>
 *
 * <p>{@code windowAggregates} keeps the per-window detail so the evaluator's
 * tools can answer "P99 during the spike window" rather than only run-wide
 * averages — an average over a whole run hides exactly the regression the user
 * needs told about.
 *
 * <p>{@code warnings} exists so an incomplete run cannot be read as a complete one. The
 * engine marks a run partial when it could not deliver every telemetry sample, and the
 * aggregator counts the samples that arrived for a window it had already published; either
 * fact makes every percentile in this event an estimate. Publishing the event anyway is
 * right — withholding it leaves a submission stuck at {@code AGGREGATING} with nothing to
 * show — but the caveat travels on the record rather than in a log line nobody reads at the
 * moment they need it. There was no field to put it in, which is how a partial trace came
 * to look identical to a complete one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SimulationSummaryEvent(
        String simulationId,
        String submissionId,
        String correlationId,
        String scenario,
        SimulationLifecycleEvent.ArchitectureDigest architecture,
        List<FailureRecord> failures,
        List<NodeSummary> nodeSummaries,
        Totals totals,
        List<AggregatedMetricEvent> windowAggregates,
        Map<String, Object> graphMetrics,
        Instant startedAt,
        Instant completedAt,
        long tickCount,
        long tickMillis,
        List<String> warnings) {

    /** One injected failure and what it actually did. Evidence, not intent. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FailureRecord(
            String nodeId,
            String nodeType,
            String failureMode,
            long startTick,
            long endTick,
            boolean recovered,
            long recoveryTimeMs,
            List<String> directlyAffectedNodeIds,
            double worstObservedLatencyMs,
            double peakObservedErrorRate) {

        public long durationTicks() {
            return Math.max(0, endTick - startTick);
        }
    }

    /** Run-wide per-component figures, pre-selected for charting and prompting. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NodeSummary(
            String nodeId,
            String nodeType,
            String label,
            double p50LatencyMs,
            double p95LatencyMs,
            double p99LatencyMs,
            double maxLatencyMs,
            double avgErrorRate,
            double peakErrorRate,
            double peakThroughputRps,
            double avgThroughputRps,
            double peakUtilization,
            double avgCacheHitRate,
            long peakQueueDepth,
            boolean wasFailureTarget,
            boolean saturated,
            long sampleCount) {

        public NodeSummary {
            if (nodeType == null) {
                nodeType = NodeType.SERVICE.getValue();
            }
            if (label == null) {
                label = nodeId;
            }
        }
    }

    /**
     * Architecture-wide figures.
     *
     * <p>The three request totals are the accounting identity and are named for it:
     * {@code totalOffered == totalServed + totalErrors + backlogedRequests}. The field was
     * previously called {@code droppedRequests} and carried
     * {@code offered - served - errors}, which is the residual backlog — so a run with
     * 1 653 requests still queued behind a slow consumer published them as 1 653
     * <em>dropped</em> requests. Nothing was dropped; a reader told otherwise goes looking
     * for packet loss in a system that queued them. Renamed rather than added, because no
     * consumer reads it yet and the old name is worse than no name.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Totals(
            double p50LatencyMs,
            double p95LatencyMs,
            double p99LatencyMs,
            double avgErrorRate,
            double peakErrorRate,
            double peakThroughputRps,
            double offeredThroughputRps,
            double totalRequests,
            double totalErrors,
            double backlogedRequests,
            int saturatedNodeCount,
            double meanRecoveryTimeMs) {

        /** Fraction of offered load that was served rather than rejected or dropped. */
        public double throughputEfficiency() {
            if (offeredThroughputRps <= 0 || peakThroughputRps <= 0) {
                return 0;
            }
            return Math.min(1.0, peakThroughputRps / offeredThroughputRps);
        }
    }

    public SimulationSummaryEvent {
        if (scenario == null) {
            scenario = ScenarioType.NORMAL.getValue();
        }
        if (failures == null) {
            failures = List.of();
        }
        if (nodeSummaries == null) {
            nodeSummaries = List.of();
        }
        if (windowAggregates == null) {
            windowAggregates = List.of();
        }
        if (graphMetrics == null) {
            graphMetrics = Map.of();
        }
        if (warnings == null) {
            warnings = List.of();
        }
        if (totals == null) {
            totals = new Totals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        if (completedAt == null) {
            completedAt = startedAt == null ? Instant.now() : startedAt;
        }
        if (startedAt == null) {
            startedAt = completedAt;
        }
    }

    /**
     * A copy of this summary carrying one more caveat.
     *
     * <p>Records are immutable and the caveats are discovered while building the event, so
     * they are added by copying rather than by mutation. Both parties to this event have
     * something the other cannot know: the engine knows how many samples it failed to
     * deliver, the aggregator knows how many arrived for a window it had already published.
     * They belong on the same record.
     */
    public SimulationSummaryEvent withWarning(String warning) {
        List<String> combined = new java.util.ArrayList<>(warnings);
        combined.add(warning);
        return new SimulationSummaryEvent(simulationId, submissionId, correlationId, scenario,
                architecture, failures, nodeSummaries, totals, windowAggregates, graphMetrics,
                startedAt, completedAt, tickCount, tickMillis, combined);
    }

    public ScenarioType scenarioType() {
        ScenarioType resolved = ScenarioType.findByValue(scenario);
        return resolved == null ? ScenarioType.NORMAL : resolved;
    }

    /** Worst P99 across all components; the single number the UI headlines. */
    public double worstNodeP99() {
        return nodeSummaries.stream()
                .mapToDouble(NodeSummary::p99LatencyMs)
                .max()
                .orElse(0);
    }

    public NodeSummary node(String nodeId) {
        return nodeSummaries.stream()
                .filter(n -> n.nodeId().equals(nodeId))
                .findFirst()
                .orElse(null);
    }
}
