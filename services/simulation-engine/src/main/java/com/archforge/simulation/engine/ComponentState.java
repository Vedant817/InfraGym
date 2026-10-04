package com.archforge.simulation.engine;

import com.archforge.contracts.stats.Distribution;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.graph.CompiledNode;
import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The mutable state of one component over one run.
 *
 * <p>Separate from {@link CompiledNode} because that type is immutable, part of the
 * wire contract, and re-read from Kafka by the aggregator and the evaluator. Anything
 * the simulation <em>learns</em> — the current backlog, the latency distribution, the
 * peak utilisation, whether the component is failing right now — belongs here.
 *
 * <h2>Latency samples are retained, not averaged</h2>
 * The product reports P95 and P99, and an average cannot recover a tail: a service
 * that is 10 ms for 99% of requests and 3 s for 1% of them has an average of 40 ms
 * and a P99 of 3 s. Those are completely different designs and a user needs to be
 * told about the second one. Retention is bounded by {@link #MAX_SAMPLES} with
 * decimation rather than a ring buffer, because a ring buffer would bias every
 * percentile toward the tail of the run and report a P99 for the last ten seconds as
 * though it described the whole run.
 *
 * <h2>Sum-based averages, not running means</h2>
 * The previous implementation kept a running latency average that was reset every
 * tick, so the "average" for a 60-second run was the average of the last second.
 * {@link #averageServedRps()} and friends accumulate totals and divide once at the
 * end, which is what "average over the run" means.
 */
@Getter
@Setter
public class ComponentState {

    /**
     * Retained latency samples per component.
     *
     * <p>A 60-second run at a 1-second tick produces 60 samples, so every sample of a
     * normal run is kept. The bound only bites on long runs, where decimation keeps
     * every other sample and therefore preserves the shape of the distribution.
     */
    static final int MAX_SAMPLES = 4096;

    /** Retained cache-outcome history, for spotting a hit-rate collapse mid-run. */
    static final int MAX_CACHE_HISTORY = 512;

    private final String nodeId;
    private final NodeType type;
    private final CompiledNode definition;
    private final NodeCharacteristics characteristics;

    /** Requests or messages waiting for this component's service slots. */
    private double queueDepth;

    /** Requests currently being served, by Little's law. */
    private double inFlight;

    /** Fraction of the last tick's arrivals that failed. */
    private double lastErrorRate;

    /** Requests per second offered to this component in the last tick. */
    private double lastOfferedRps;

    /** Requests per second accepted by this component in the last tick. */
    private double lastServedRps;

    /**
     * Requests per second this component passes to its downstream in the last tick.
     *
     * <p>Not the same as {@link #lastServedRps}, and the difference is the whole
     * cache-outage story: a cache accepts every request but forwards only its misses,
     * so its downstream sees {@code served * (1 - hitRate)}. A failing cache forwards
     * all of them. With a single "throughput" number the origin's load was
     * independent of the cache's health, which is precisely backwards.
     */
    private double lastBypassRps;

    /**
     * End-to-end latency of the slowest path through this component, in milliseconds.
     *
     * <p>Accumulated along the DAG as each node is evaluated: the worst upstream path
     * plus this link's propagation delay plus this component's service time. It does
     * <em>not</em> include queueing delay, and that is a decision rather than an
     * omission: backlog is reported separately as {@link #getQueueDepth()} and
     * {@link #getQueueAgeMs()}, because it belongs to the queue in front of a component
     * rather than to the component. Reporting the worst path rather than the worst local
     * service time is what turns "each stage is 20 ms" into "the user waits 100 ms",
     * which is the lesson the product exists to teach.
     */
    private double lastPathLatencyMs;

    /**
     * Saturation of the last tick: offered requests against what could have been served.
     *
     * <p>Crosses 1 when the component is asked for more than it can deliver, and stays
     * above it in proportion. Measuring served-against-capacity instead would pin the
     * value at exactly 1.0 the instant a component saturates, making "overloaded" and
     * "running at capacity" the same number.
     */
    private double lastUtilization;

    /** Cache hit rate over the whole run, or -1 when no lookup was recorded. */
    private double cacheHitRate = -1;

    /** Oldest queued item's age in milliseconds; the user-visible staleness signal. */
    private double queueAgeMs;

    /** Whether this component is currently in a planned failure. */
    private boolean failing;

    /** Ticks for which a failure was active. */
    private int failureTicks;

    /** Ticks spent recovering after a failure ended, in milliseconds. */
    private double recoveryTimeMs = -1;

    /** Misses that reached no downstream component because the node has no edges. */
    private double unroutedBypassRequests;

    // --- run-wide totals -------------------------------------------------------

    private double servedTotal;
    private double offeredTotal;
    private double failedTotal;
    private double utilizationTotal;
    private double saturatedTicks;
    private long observedTicks;
    private double cacheHits;
    private double cacheLookups;
    private double maxQueueAgeMs;

    private double peakServedRps;
    private double peakErrorRate;
    private double peakUtilization;

    /** Highest per-second volume this component was actually able to take, per tick. */
    private double peakServiceableRps;
    private double peakQueueDepth;
    private double maxPathLatencyMs;

    private final Map<Long, Boolean> cacheOutcomes = new LinkedHashMap<>();
    private final LatencySamples latencySamples = new LatencySamples();

    public ComponentState(CompiledNode definition, NodeCharacteristics characteristics) {
        this.definition = definition;
        this.nodeId = definition.getId();
        this.type = definition.getType();
        this.characteristics = characteristics;
    }

    /** Records one completed request's end-to-end latency. */
    public void recordLatency(double millis) {
        latencySamples.add(millis);
        observedTicks++;
        if (millis > maxPathLatencyMs) {
            maxPathLatencyMs = millis;
        }
    }

    /** Records a cache lookup outcome, decoupled from the request count. */
    public void recordCacheLookup(long requests, boolean hit) {
        if (requests <= 0) {
            return;
        }
        cacheLookups += requests;
        if (hit) {
            cacheHits += requests;
        }
        cacheHitRate = cacheLookups == 0 ? -1 : cacheHits / cacheLookups;
        cacheOutcomes.put((long) cacheLookups, hit);
        while (cacheOutcomes.size() > MAX_CACHE_HISTORY) {
            var iterator = cacheOutcomes.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    /**
     * Folds one tick's outcome into the run totals.
     *
     * <p>Called once per tick per component, after the tick's queues and rates have
     * been settled, so peaks and averages are taken over settled values.
     */
    public void accumulateTick(double servedThisTick,
                               double offeredThisTick,
                               double failedThisTick,
                               double servedRps,
                               double errorRate,
                               double utilization) {
        servedTotal += servedThisTick;
        offeredTotal += offeredThisTick;
        failedTotal += failedThisTick;
        utilizationTotal += utilization;
        peakServedRps = Math.max(peakServedRps, servedRps);
        peakErrorRate = Math.max(peakErrorRate, errorRate);
        peakUtilization = Math.max(peakUtilization, utilization);
        peakQueueDepth = Math.max(peakQueueDepth, queueDepth);
        maxQueueAgeMs = Math.max(maxQueueAgeMs, queueAgeMs);
        if (utilization >= 1.0) {
            saturatedTicks++;
        }
    }

    /**
     * Records the ceiling this component was actually measured against, in requests per
     * second, for the tick just evaluated.
     *
     * <p>Not the declared rate. For most components they are the same, but a queue's is
     * not: it is bounded by <em>its consumer's</em> drain rate, because a broker accepts
     * messages far faster than any consumer group reads them. A live flash-sale run
     * therefore produced the finding "briefly exceeded its declared ceiling of 10000.0
     * rps" for a queue whose peak throughput was 100 rps and whose backlog reached 2546
     * messages — a claim about a number the component never approached, on the one
     * finding a reader would act on. Naming the drain rate instead makes it true:
     * "could only take 100 rps".
     */
    public void observeCeilingRps(double ceilingRps) {
        if (ceilingRps > peakServiceableRps) {
            peakServiceableRps = ceilingRps;
        }
    }

    /** Clears the per-tick fields. Running totals are deliberately left alone. */
    public void resetTickState() {
        lastErrorRate = 0;
        lastOfferedRps = 0;
        lastServedRps = 0;
        lastBypassRps = 0;
        lastPathLatencyMs = 0;
        lastUtilization = 0;
    }

    /** Whether this component was asked for more than it could deliver at least once. */
    public boolean isSaturated() {
        return peakUtilization >= 1.0;
    }

    /** Whether it was saturated in at least half the ticks it was observed. */
    public boolean isPersistentlySaturated() {
        return observedTicks > 0 && saturatedTicks * 2 >= observedTicks;
    }

    /**
     * Whether it was saturated in a majority of ticks.
     *
     * <p>Named for what it measures. The previous version reported "saturated" for a
     * component that crossed its ceiling on a single tick during a flash sale, which is
     * the expected behaviour of every buffered component and is therefore not a finding.
     */

    /** Whether this component was ever a planned failure target. */
    public boolean wasFailureTarget() {
        return failureTicks > 0;
    }

    /** Distribution of the recorded end-to-end latencies. */
    public Distribution latencyDistribution() {
        return Distribution.of(latencySamples.toArray());
    }

    /** Total latency samples recorded, including any discarded by decimation. */
    public long latencySampleCount() {
        return latencySamples.totalAdded();
    }

    public double averageServedRps() {
        return observedTicks == 0 ? 0 : servedTotal / observedTicks;
    }

    public double averageOfferedRps() {
        return observedTicks == 0 ? 0 : offeredTotal / observedTicks;
    }

    /**
     * Failed requests as a fraction of offered ones, over the whole run.
     *
     * <p>Guarded on {@code offeredTotal}, not only on {@code observedTicks}. Without it a
     * component that was offered nothing at all yields {@code 0 / 0} — {@code NaN} — and
     * {@code NaN} is not neutral downstream: it reaches {@code NodeReport.avgErrorRate},
     * the summary event, and the {@code high_error_rate} finding, where
     * {@code NaN > 0.01} is false and the finding is silently suppressed. A run reports
     * a NaN as a metric and quietly declines to comment on it.
     *
     * <p>Reachable in practice: a store behind a cache declaring {@code hitRate = 1.0} is
     * offered nothing on every tick, because the cache's miss volume is zero.
     */
    public double averageErrorRate() {
        if (observedTicks == 0 || offeredTotal <= 0) {
            return 0;
        }
        return failedTotal / offeredTotal;
    }

    public double averageUtilization() {
        return observedTicks == 0 ? 0 : utilizationTotal / observedTicks;
    }

    public double servedTotal() {
        return servedTotal;
    }

    public double offeredTotal() {
        return offeredTotal;
    }

    public double failedTotal() {
        return failedTotal;
    }

    /** Work still sitting in this component's queue when the run ended. */
    public double residualBacklog() {
        return queueDepth;
    }

    /** Highest per-second volume this component was actually able to take. */
    public double peakServiceableRps() {
        return peakServiceableRps;
    }

    public long observedTicks() {
        return observedTicks;
    }

    public double saturatedTicks() {
        return saturatedTicks;
    }

    public double cacheHitRate() {
        return cacheHitRate;
    }

    public double cacheLookups() {
        return cacheLookups;
    }

    public double cacheHits() {
        return cacheHits;
    }

    public double peakQueueDepth() {
        return peakQueueDepth;
    }

    public double maxQueueAgeMs() {
        return maxQueueAgeMs;
    }

    public double maxPathLatencyMs() {
        return maxPathLatencyMs;
    }

    /**
     * A bounded, decimating sample buffer.
     *
     * <p>Keeps every sample up to the cap, then every other one. Decimation preserves
     * the distribution's shape; a ring buffer would not.
     */
    static final class LatencySamples {
        private double[] values = new double[256];
        private int size;
        private long totalAdded;

        void add(double millis) {
            totalAdded++;
            if (size == values.length) {
                if (values.length >= MAX_SAMPLES) {
                    int kept = 0;
                    for (int i = 0; i < values.length; i += 2) {
                        values[kept++] = values[i];
                    }
                    size = kept;
                } else {
                    double[] grown = new double[Math.min(MAX_SAMPLES, values.length * 2)];
                    System.arraycopy(values, 0, grown, 0, size);
                    values = grown;
                }
            }
            values[size++] = millis;
        }

        int size() {
            return size;
        }

        long totalAdded() {
            return totalAdded;
        }

        double[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
