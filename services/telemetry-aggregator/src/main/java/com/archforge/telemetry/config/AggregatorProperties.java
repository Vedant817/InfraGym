package com.archforge.telemetry.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How this service turns samples into windows.
 *
 * <p>The previous configuration exposed {@code window-size-seconds} and
 * {@code window-grace-seconds}, because the aggregation was Kafka Streams wall-clock
 * windowing. Neither applies any more: windows are ranges of simulated ticks, so the
 * meaningful unit is the tick, and a grace period is meaningless when a window closes the
 * moment a sample for the next one arrives — the engine's per-partition ordering is what
 * guarantees nothing is still in flight, not a timer.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "archforge.aggregator")
public class AggregatorProperties {

    /**
     * Ticks per window.
     *
     * <p>The resolution of every chart the product draws, and the only knob here that
     * changes a reported number. Ten ticks is 10 simulated seconds at the default tick
     * length, which is coarse enough to smooth sampling noise and fine enough to show where
     * in a ramp a component started to fall over.
     */
    private long windowSizeTicks = 10;

    /**
     * Simulated milliseconds per tick.
     *
     * <p>Used only to place a window on a time axis. The telemetry record does not carry
     * the run's tick length, so this must match the engine's configured default; it is
     * here so that a deployment which changes the engine's tick length has one obvious
     * place to change it here too.
     */
    private long tickMillis = 1000L;

    /**
     * How long a terminal lifecycle event waits for its run's last samples, in
     * milliseconds.
     *
     * <p>Zero disables parking, which is correct for a single-topic test and wrong in
     * production: two consumers on two topics have no ordering relationship, and a roll-up
     * built before the last samples arrive is a roll-up with a hole in it.
     */
    private long terminalGraceMillis = 2_000L;

    /** Runs whose samples the aggregator will hold before evicting the oldest. */
    private int maxInFlightRuns = 256;

    /**
     * Terminal events held at once, as a second bound.
     *
     * <p>Separate from the in-flight bound because a terminal event with no samples behind
     * it consumes nothing, and bounding only the sample-holding map would let a stream of
     * rejected runs park an unbounded number of actions.
     */
    private int maxParkedTerminals = 256;

    /** Kafka consumer group. Distinct from the engine's, or they would steal each other's records. */
    private String consumerGroup = "telemetry-aggregator-group";

    /** Sink topic for {@link com.archforge.contracts.event.AggregatedMetricEvent}. */
    private String aggregatedMetricsTopic = "aggregated.metrics";

    /** Sink topic for the whole-run {@link com.archforge.contracts.event.SimulationSummaryEvent}. */
    private String aggregatedSummaryTopic = "aggregated.summary";

    /** How many samples one window retains before its percentiles are marked approximate. */
    private int maxSamplesPerWindow = 2_000;

    /** Whether to build and publish the whole-run summary as well as the windowed series. */
    private boolean publishSummary = true;

    /** How often parked terminal events are checked for expiry. */
    private long parkedSweepMillis = 500L;
}