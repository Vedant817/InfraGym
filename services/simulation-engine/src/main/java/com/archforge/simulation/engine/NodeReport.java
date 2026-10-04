package com.archforge.simulation.engine;

import java.util.Map;

/**
 * What one component actually did over a run, as opposed to what it declared.
 *
 * <p>This is the engine's contribution to the report. The aggregator builds the
 * authoritative {@code SimulationSummaryEvent} from the telemetry stream — this is a
 * second, independent reading of the same numbers, published on the same topic so a
 * consumer can cross-check one against the other. If the two disagree, something is
 * wrong in the pipeline, and having both makes that visible instead of leaving it to
 * be inferred.
 *
 * <p>Percentiles come from {@link Distribution} over the retained per-tick latency
 * samples. They are computed here rather than only downstream because the engine
 * holds the samples; the aggregator recomputes them from the wire, which is the point.
 *
 * @param nodeId              component id
 * @param nodeType            wire type value
 * @param label               display label
 * @param p50LatencyMs        median end-to-end latency
 * @param p95LatencyMs        95th percentile end-to-end latency
 * @param p99LatencyMs        99th percentile end-to-end latency
 * @param maxLatencyMs        worst observed end-to-end latency
 * @param avgErrorRate        failed requests / offered requests across the run
 * @param peakErrorRate       worst single-tick error rate
 * @param peakThroughputRps   highest requests-per-second served
 * @param avgThroughputRps    mean requests-per-second served
 * @param peakUtilization     highest offered/capacity ratio; >= 1 means saturated
 * @param avgUtilization      mean offered/capacity ratio over the run's ticks
 * @param avgCacheHitRate     mean cache hit rate, or -1 when not a cache
 * @param peakQueueDepth      deepest backlog seen
 * @param maxQueueAgeMs       oldest queued item's age at its worst
 * @param wasFailureTarget    whether this component was a planned failure target
 * @param saturated           whether it hit its declared ceiling at any point
 * @param persistentlySaturated whether it was at its ceiling in >= half its ticks
 * @param recoveryTimeMs      observed recovery after a failure, or -1 if none
 * @param failureTicks        ticks spent failing
 * @param sampleCount         latency samples the percentiles are computed from
 * @param unroutedBypassRequests load forwarded to no downstream component
 * @param declaredRateRps     capacity the compiler declared, for comparison
 * @param observedCeilingRps  highest rate this component could actually take per second;
 *                            differs from {@code declaredRateRps} for a queue, which is
 *                            bounded by its consumer's drain rate
 */
public record NodeReport(
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
        double avgUtilization,
        double avgCacheHitRate,
        double peakQueueDepth,
        double maxQueueAgeMs,
        boolean wasFailureTarget,
        boolean saturated,
        boolean persistentlySaturated,
        double recoveryTimeMs,
        int failureTicks,
        long sampleCount,
        double unroutedBypassRequests,
        double declaredRateRps,
        double observedCeilingRps) {

    public static NodeReport from(ComponentState state) {
        Distribution latency = state.latencyDistribution();
        NodeCharacteristics traits = state.getCharacteristics();
        return new NodeReport(
                state.getNodeId(),
                state.getType().getValue(),
                state.getDefinition().getLabel(),
                latency.p50(),
                latency.p95(),
                latency.p99(),
                latency.max(),
                state.averageErrorRate(),
                state.getPeakErrorRate(),
                state.getPeakServedRps(),
                state.averageServedRps(),
                state.getPeakUtilization(),
                state.averageUtilization(),
                state.getCacheHitRate(),
                state.getPeakQueueDepth(),
                state.getMaxQueueAgeMs(),
                state.wasFailureTarget(),
                state.isSaturated(),
                state.isPersistentlySaturated(),
                state.getRecoveryTimeMs(),
                state.getFailureTicks(),
                state.latencySampleCount(),
                state.getUnroutedBypassRequests(),
                traits.sustainedRateRps(),
                state.peakServiceableRps());
    }

    /**
     * Whether this component's behaviour is worth telling the user about.
     *
     * <p>Evaluated against {@code contracts.lifecycle} free thresholds: an evaluator
     * handed every node's report should be able to skip the healthy ones, and a
     * report that flags nothing is more useful than one that flags everything.
     */
    public Map<String, Object> findings() {
        Map<String, Object> findings = new java.util.LinkedHashMap<>();
        if (persistentlySaturated) {
            findings.put("saturated", "at its ceiling of " + ceiling()
                    + " rps for more than half the run");
        } else if (saturated) {
            findings.put("saturated", "briefly exceeded its ceiling of " + ceiling() + " rps");
        }
        if (avgErrorRate > 0.01) {
            findings.put("error_rate", "failed " + Math.round(avgErrorRate * 100) + "% of requests");
        }
        if (peakQueueDepth > 0) {
            findings.put("backlog", "up to " + Math.round(peakQueueDepth)
                    + " requests queued behind a consumer taking " + ceiling() + " rps");
        }
        if (maxQueueAgeMs > 1_000) {
            findings.put("queue_age_ms", "oldest queued item reached " + Math.round(maxQueueAgeMs) + " ms old");
        }
        if (unroutedBypassRequests > 0) {
            findings.put("unrouted_bypass", Math.round(unroutedBypassRequests)
                    + " requests were forwarded to no downstream component");
        }
        if (avgCacheHitRate >= 0 && avgCacheHitRate < 0.5) {
            findings.put("cache_hit_rate", "hit rate of " + Math.round(avgCacheHitRate * 100)
                    + "% is below the point where a cache pays for itself");
        }
        if (wasFailureTarget && recoveryTimeMs > 0) {
            findings.put("recovery", "recovered after " + Math.round(recoveryTimeMs) + " ms");
        }
        return findings;
    }

    /**
     * The ceiling to quote in a finding, in requests per second.
     *
     * <p>The rate the component was actually measured against, not its declared one. They
     * agree for an ordinary component and disagree for a queue, whose limit is its
     * consumer's drain rate — so quoting the declared rate produced "exceeded its declared
     * ceiling of 10000 rps" on a queue that peaked at 100 rps with 2 546 messages backed
     * up behind it. A finding naming a number the component never approached is worse than
     * no finding, because it is the one a reader acts on.
     */
    private String ceiling() {
        double observed = observedCeilingRps > 0 ? observedCeilingRps : declaredRateRps;
        return String.valueOf(Math.round(observed));
    }
}
