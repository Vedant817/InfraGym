package com.archforge.contracts.event;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.lifecycle.MetricUnits;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * One metric over one aggregation window. Produced by the telemetry aggregator on
 * {@code aggregated.metrics} for time-series charts, and rolled up into the final
 * per-simulation totals carried by {@link SimulationSummaryEvent}.
 *
 * <p>Percentiles are computed from the raw samples in the window, not estimated
 * from min/avg/max. That distinction is the whole point: a min/avg/max aggregate
 * can hide the tail latency that makes or breaks an architecture, and the README
 * promises P95/P99 because tail behaviour under load is what this platform is
 * for.
 *
 * <p>{@code sum} is carried alongside {@code avg} so the aggregator can merge
 * weighted averages across windows correctly instead of averaging averages.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AggregatedMetricEvent(
        String simulationId,
        String submissionId,
        String nodeId,
        String nodeType,
        String metricName,
        String unit,
        long windowStartTick,
        long windowEndTick,
        Instant windowStart,
        Instant windowEnd,
        long count,
        double min,
        double max,
        double avg,
        double sum,
        double p50,
        double p95,
        double p99,
        double stdDev) {

    public AggregatedMetricEvent {
        if (nodeType == null) {
            nodeType = NodeType.SERVICE.getValue();
        }
        if (unit == null) {
            unit = MetricUnits.forMetric(metricName);
        }
        if (windowStart == null || windowEnd == null) {
            Instant now = Instant.now();
            if (windowStart == null) {
                windowStart = now;
            }
            if (windowEnd == null) {
                windowEnd = now;
            }
        }
    }

    /** Whether this aggregate is latency-shaped and therefore has meaningful percentiles. */
    public boolean isLatencyShaped() {
        return unit != null && MetricUnits.MILLISECOND.equals(unit);
    }

    /** Empty aggregate for a window that saw no samples. */
    public static AggregatedMetricEvent empty(String simulationId, String nodeId,
                                              NodeType nodeType, String metricName,
                                              long windowStartTick, long windowEndTick,
                                              Instant windowStart, Instant windowEnd) {
        String unit = MetricUnits.forMetric(metricName);
        return new AggregatedMetricEvent(simulationId, null, nodeId,
                nodeType == null ? null : nodeType.getValue(), metricName, unit,
                windowStartTick, windowEndTick, windowStart, windowEnd,
                0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
