package com.archforge.contracts.event;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.lifecycle.MetricNames;
import com.archforge.contracts.lifecycle.MetricUnits;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * One measurement for one component at one point in simulated time. Produced by
 * the simulation engine on {@code simulation.telemetry}, consumed by the
 * telemetry aggregator.
 *
 * <p>{@code tick} is the engine's simulation-time clock, not wall-clock time.
 * The aggregator windows on it, which means a run that takes 40 real seconds to
 * simulate 60 simulated seconds still produces correctly aligned windows — and a
 * re-run of the same seed produces byte-identical windows.
 *
 * <p>{@code nodeType} travels with every sample so the aggregator can roll up
 * "worst database utilisation" without a second lookup, and {@code phase} names
 * the workload phase (warmup, spike, recovery…) so a chart can shade phases and
 * the evaluator can say "during the spike, not on average".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelemetryEvent(
        String simulationId,
        String submissionId,
        String nodeId,
        String nodeType,
        String metricName,
        double value,
        String unit,
        long tick,
        String phase,
        boolean failureActive,
        Instant timestamp) {

    public TelemetryEvent {
        if (nodeType == null) {
            nodeType = NodeType.SERVICE.getValue();
        }
        if (unit == null) {
            unit = MetricUnits.forMetric(metricName);
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    public static TelemetryEvent of(String simulationId,
                                    String submissionId,
                                    String nodeId,
                                    NodeType nodeType,
                                    String metricName,
                                    double value,
                                    long tick,
                                    String phase,
                                    boolean failureActive) {
        return new TelemetryEvent(simulationId, submissionId, nodeId,
                nodeType == null ? null : nodeType.getValue(),
                metricName, value, MetricUnits.forMetric(metricName),
                tick, phase, failureActive, Instant.now());
    }

    /** Convenience for the common latency sample. */
    public static TelemetryEvent latency(String simulationId, String submissionId, String nodeId,
                                         NodeType nodeType, double millis, long tick,
                                         String phase, boolean failureActive) {
        return of(simulationId, submissionId, nodeId, nodeType, MetricNames.LATENCY_MS,
                millis, tick, phase, failureActive);
    }

    public static TelemetryEvent errorRate(String simulationId, String submissionId, String nodeId,
                                           NodeType nodeType, double rate, long tick,
                                           String phase, boolean failureActive) {
        return of(simulationId, submissionId, nodeId, nodeType, MetricNames.ERROR_RATE,
                rate, tick, phase, failureActive);
    }
}
