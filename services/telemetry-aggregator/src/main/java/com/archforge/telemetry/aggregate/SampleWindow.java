package com.archforge.telemetry.aggregate;

import com.archforge.contracts.event.TelemetryEvent;
import com.archforge.contracts.stats.Distribution;

import java.util.ArrayList;
import java.util.List;

/**
 * Every sample of one metric for one component within one tick window.
 *
 * <p><b>Why the values are kept, not just min/max/avg.</b> A min/max/avg aggregate cannot
 * produce a percentile, and a percentile cannot be recovered from one afterwards. The
 * contract has carried {@code p50}, {@code p95}, {@code p99} and {@code stdDev} since it
 * was written, and the aggregator published a private 10-field type instead — so every
 * percentile the platform advertises was structurally impossible to produce. Tail latency
 * is the thing this whole product exists to reason about, and max is not a substitute: it
 * is a different statistic, and reporting it as though it described the tail makes a design
 * with one 3-second outlier indistinguishable from one that is uniformly slow.
 *
 * <p>Memory is bounded by the run, not by the stream. A window closes after a bounded
 * number of ticks and is evicted when it closes, so what is retained is
 * {@code nodes x metrics x samplesPerWindow} rather than the whole trace. At the engine's
 * documented limits — 500 nodes, 9 metrics, a 10-tick window — that is 4 500 doubles per
 * window in flight, and one window at a time.
 */
public final class SampleWindow {

    /**
     * Samples retained per window, as a guard rather than a design parameter.
     *
     * <p>A 500-node run at a 1 ms tick would put 500 samples per metric per window, so this
     * is above the realistic case. When it is exceeded the window is marked truncated and
     * its percentiles are computed over the first {@code limit} samples rather than
     * silently over a partial set — a stated approximation is usable, an unstated one is
     * not.
     */
    static final int MAX_SAMPLES = 2_000;

    private final String nodeId;
    private final String metricName;
    private final long windowIndex;

    private final List<Double> values = new ArrayList<>();
    private double sum;
    private double min = Double.MAX_VALUE;
    private double max = -Double.MAX_VALUE;
    private long observed;
    private long firstTick = Long.MAX_VALUE;
    private long lastTick = Long.MIN_VALUE;
    private boolean truncated;

    public SampleWindow(String nodeId, String metricName, long windowIndex) {
        this.nodeId = nodeId;
        this.metricName = metricName;
        this.windowIndex = windowIndex;
    }

    public void add(TelemetryEvent event) {
        observed++;
        if (values.size() < MAX_SAMPLES) {
            values.add(event.value());
        } else {
            truncated = true;
        }
        // Sum, min, max and the tick range are maintained regardless of whether the value
        // was retained, so a truncated window still reports honest totals.
        sum += event.value();
        min = Math.min(min, event.value());
        max = Math.max(max, event.value());
        firstTick = Math.min(firstTick, event.tick());
        lastTick = Math.max(lastTick, event.tick());
    }

    public String nodeId() {
        return nodeId;
    }

    public String metricName() {
        return metricName;
    }

    public long windowIndex() {
        return windowIndex;
    }

    /** Samples offered to this window, including any beyond the retention cap. */
    public long count() {
        return observed;
    }

    public long retainedCount() {
        return values.size();
    }

    public boolean truncated() {
        return truncated;
    }

    public double min() {
        return values.isEmpty() ? 0 : min;
    }

    public double max() {
        return values.isEmpty() ? 0 : max;
    }

    public double sum() {
        return sum;
    }

    public double avg() {
        return values.isEmpty() ? 0 : sum / values.size();
    }

    /** First tick seen in this window, or -1 when empty. */
    public long firstTick() {
        return firstTick == Long.MAX_VALUE ? -1 : firstTick;
    }

    /** Last tick seen in this window, or -1 when empty. */
    public long lastTick() {
        return lastTick == Long.MIN_VALUE ? -1 : lastTick;
    }

    /**
     * The distribution over the retained samples.
     *
     * <p>Shared with the engine so a component's percentiles in this run's summary and in
     * the engine's own report cannot disagree — same method, same tie-breaking.
     */
    public Distribution distribution() {
        double[] array = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            array[i] = values.get(i);
        }
        return Distribution.of(array);
    }

    /** Folds this window's samples into another, for a whole-run roll-up. */
    public void mergeInto(List<Double> target) {
        for (double value : values) {
            if (target.size() < MAX_SAMPLES) {
                target.add(value);
            }
        }
    }
}