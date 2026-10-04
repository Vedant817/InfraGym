package com.archforge.telemetry.aggregate;

/**
 * Window arithmetic on <em>simulated</em> ticks.
 *
 * <p><b>Why not wall-clock windows.</b> The aggregator used Kafka Streams' time-based
 * windowing with a 10-second window, which is wrong in three separate ways for this
 * pipeline:
 *
 * <ul>
 *   <li><b>It does not align with the data.</b> A 60-tick simulation at a 1 s tick executes
 *       in about 350 ms of wall clock, so a whole run lands inside a single 10 s window and
 *       every chart is one flat line — the shape of the workload, which is a ramp to 10x
 *       and back, is gone. Worse, the boundary lands wherever the wall clock happened to
 *       fall, so two runs of the same design window differently.</li>
 *   <li><b>It merges unrelated runs.</b> Two concurrent simulations both emit into the
 *       same wall-clock windows, and nothing in the key separates them: the aggregate was
 *       keyed {@code simulationId:nodeId} <em>after</em> grouping by
 *       {@code simulationId:nodeId:metricName}, so windows were not per-run at all.</li>
 *   <li><b>Its output key was not the run's key.</b> Re-keying the result to
 *       {@code simulationId:nodeId} after a per-{@code nodeId,metricName} grouping
 *       destroyed the one-partition-per-run guarantee the engine's telemetry keying exists
 *       to provide, so a run's windows could be interleaved with another's downstream.</li>
 * </ul>
 *
 * <p>Windows here are ranges of tick indices. {@code windowOf(tick, size) = tick / size},
 * so a window is {@code [index*size, (index+1)*size)} and every sample in it was produced
 * at the same point in the simulated timeline. A window therefore means the same thing for
 * a run that took 40 ms and one that took 4 minutes, and two runs never share one however
 * they overlap in real time.
 *
 * <p>It also sidesteps Kafka Streams' stream-time monotonicity requirement, which the
 * obvious alternative runs straight into: several runs share a partition, each restarting
 * at tick 0, so a synthetic timestamp derived from the tick goes backwards at every run
 * boundary and the windowing DSL produces undefined results. Tick arithmetic has no such
 * constraint because this aggregator does its own bookkeeping in record order.
 */
public final class TickWindow {

    private TickWindow() {
    }

    /** Index of the window a tick falls in. */
    public static long indexOf(long tick, long windowSize) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("Window size must be positive, was " + windowSize);
        }
        return Math.floorDiv(tick, windowSize);
    }

    /** First tick of a window. */
    public static long startOf(long index, long windowSize) {
        return Math.multiplyExact(index, windowSize);
    }

    /** One past the last tick of a window. */
    public static long endOf(long index, long windowSize) {
        return Math.addExact(startOf(index, windowSize), windowSize);
    }

    /** Every window index from the first to the last, inclusive. Used to fill gaps. */
    public static long countThrough(long fromIndex, long toIndex) {
        return Math.max(0, toIndex - fromIndex + 1);
    }

    /**
     * Simulated time a tick represents, in milliseconds.
     *
     * <p>Published on every aggregate so a consumer can place it on a time axis, and
     * computed from the tick rather than from {@code Instant.now()} so the axis is the
     * simulation's own. A chart labelled with wall-clock receipt times would show a run
     * that took 350 ms as a spike and one that took 4 minutes as a plateau, when the two
     * simulated the same 60 seconds.
     */
    public static long startMillisOf(long index, long windowSize, long tickMillis) {
        return Math.multiplyExact(startOf(index, windowSize), tickMillis);
    }
}