package com.archforge.design.domain;

import com.archforge.contracts.event.AggregatedMetricEvent;
import com.archforge.contracts.event.SimulationSummaryEvent;

import java.util.UUID;

/**
 * One component's figures for one run.
 *
 * <p>A plain record rather than a JPA entity, written through
 * {@code NodeMetricRepository} with {@code INSERT ... ON CONFLICT DO UPDATE}.
 *
 * <p>That is a deliberate choice against JPA here. The natural key is
 * {@code (simulationId, nodeId)}, and a redelivered summary must upsert rather than append --
 * otherwise every rebalance duplicates each component and every aggregate computed from this
 * table silently counts every node twice. JPA would do a select-then-insert to achieve that,
 * which is a race between two consumer threads on the same partition and does N round trips
 * where one statement will do. {@code ON CONFLICT} states the intent in the schema, is
 * atomic, and makes the redelivery a no-op by construction rather than by convention.
 *
 * <p>Percentiles are columns because they are what every comparison sorts by; the remaining
 * figures ride along in {@code extra} because nothing outside a detail panel needs them by
 * name and a migration per metric would be the wrong trade.
 */
public record NodeMetric(
        UUID simulationId,
        String nodeId,
        String nodeType,
        long sampleCount,
        double p50Ms,
        double p95Ms,
        double p99Ms,
        double maxMs,
        double avgErrorRate,
        double peakErrorRate,
        double peakRps,
        double avgRps,
        double peakUtilization,
        double avgCacheHitRate,
        long peakQueueDepth,
        boolean saturated,
        String extra) {

    public static NodeMetric of(UUID simulationId, SimulationSummaryEvent.NodeSummary node) {
        return new NodeMetric(
                simulationId, node.nodeId(), node.nodeType(), node.sampleCount(),
                node.p50LatencyMs(), node.p95LatencyMs(), node.p99LatencyMs(), node.maxLatencyMs(),
                node.avgErrorRate(), node.peakErrorRate(), node.peakThroughputRps(),
                node.avgThroughputRps(), node.peakUtilization(), node.avgCacheHitRate(),
                node.peakQueueDepth(), node.saturated(), null);
    }

    /**
     * Whether this component reported anything at all.
     *
     * <p>Distinguished from "was fast". A component with no samples has a P99 of zero, and
     * zero is the best-looking number on the page -- so a reader, and any chart built from
     * this table, must be able to tell "never measured" from "measured as instantaneous".
     */
    public boolean measured() {
        return sampleCount > 0;
    }

    /** One metric of one component over one tick window. */
    public record WindowMetric(
            UUID simulationId,
            String nodeId,
            String metricName,
            long windowStartTick,
            long windowEndTick,
            long sampleCount,
            double minValue,
            double maxValue,
            double avgValue,
            double sumValue,
            double p50,
            double p95,
            double p99,
            double stdDev,
            String unit) {

        public static WindowMetric of(UUID simulationId, AggregatedMetricEvent aggregate) {
            return new WindowMetric(
                    simulationId, aggregate.nodeId(), aggregate.metricName(),
                    aggregate.windowStartTick(), aggregate.windowEndTick(), aggregate.count(),
                    aggregate.min(), aggregate.max(), aggregate.avg(), aggregate.sum(),
                    aggregate.p50(), aggregate.p95(), aggregate.p99(), aggregate.stdDev(),
                    aggregate.unit());
        }
    }
}