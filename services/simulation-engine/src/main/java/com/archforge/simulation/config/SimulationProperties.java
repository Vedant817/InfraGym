package com.archforge.simulation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything about how a run executes, in one typed place.
 *
 * <p>The previous engine read its defaults from a nested YAML block that no class
 * referenced — {@code simulation.workload.default-rps: 1000} was in the file, and the
 * code had its own {@code 1000} literal. Two sources of truth for one number, and
 * changing the YAML did nothing. Binding the properties means the value a deployment
 * sets is the value the engine uses.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "archforge.simulation")
public class SimulationProperties {

    /**
     * Simulated seconds per run when the task does not say.
     *
     * <p>Only a default: the compiler sends an explicit duration, and a task's own
     * value always wins.
     */
    private long defaultDurationSeconds = 60;

    /**
     * Simulated milliseconds per tick when the task does not say.
     *
     * <p>Floored at {@code SimulationRequestedEvent.MIN_TICK_MILLIS}. A tick below
     * 100 ms does not add fidelity for a queueing model — it multiplies the number of
     * ticks, and therefore the telemetry, by ten, for the same answer.
     */
    private long defaultTickMillis = 1000;

    /**
     * Hard ceiling on ticks per run.
     *
     * <p>A tick count is attacker-influenced: it arrives in the task, and a task
     * claiming a 30-day run at a 100 ms tick is 25.9 million ticks per node. Without
     * a ceiling one malformed record occupies a worker until the process dies. The
     * simulator itself is fast, but the telemetry volume is what actually runs out of
     * memory, so the bound is enforced before any tick executes.
     */
    private long maxTicksPerRun = 20_000;

    /** Largest graph accepted for simulation, in nodes. */
    private int maxNodes = 500;

    /**
     * Telemetry samples a single run may emit.
     *
     * <p><b>This is the ceiling that actually binds.</b> A tick count on its own does not
     * bound anything useful: the engine emits 7-11 samples per node per tick, so the
     * largest combination the other two settings accept — {@code maxTicksPerRun} ticks of
     * {@code maxNodes} nodes — asks for 90 million samples into a 200 000 buffer. At that
     * point 0.22% of the trace survives and the run reports {@code COMPLETED}, which is
     * the worst possible outcome: a confident, complete-looking report built from
     * nothing.
     *
     * <p>{@link #getMaxSamplesPerRun()} is converted into an effective tick ceiling per
     * run, so the bound adapts to the graph's size rather than assuming the largest one.
     */
    private long maxSamplesPerRun = 2_000_000;

    /**
     * Metrics the engine emits per node per tick, on average.
     *
     * <p>Used only to convert {@link #getMaxSamplesPerRun()} into a tick ceiling. The
     * engine's core set is 7 metrics, with 2 more for a messaging node, 1 for a cache
     * and 2 for storage; 9 is a realistic average for a mixed design and is deliberately
     * an over-estimate, since a ceiling computed from an under-estimate would not bind.
     */
    private int metricsPerNode = 9;

    /**
     * Telemetry samples buffered per run before the oldest are dropped.
     *
     * <p>A 60-tick, 20-node run emits 60 * 20 * ~9 = ~10 800 samples. The buffer is
     * sized well above that so it only fills when the broker is slow. It is bounded
     * rather than unbounded because an unbounded queue converts a broker outage into
     * an out-of-memory kill, which loses the run entirely instead of degrading it.
     */
    private int telemetryBufferSize = 200_000;

    /**
     * Telemetry samples per Kafka send.
     *
     * <p>Batching is not an optimisation here. A 6-node, 60-tick run emits ~3 200
     * messages; sent one at a time that is 3 200 round trips and the simulation
     * outruns the producer, so the buffer grows and the run is declared timed out
     * while the broker is perfectly healthy.
     */
    private int telemetryBatchSize = 500;

    /** Worker threads executing simulations concurrently. */
    private int workerThreads = 2;

    /**
     * Simulations queued before a consumer thread starts executing them itself.
     *
     * <p>When the queue is full the submitting thread runs the simulation inline
     * instead of rejecting it. That is deliberate: rejecting would drop a task whose
     * offset has already been committed, and queueing without bound would trade a
     * throughput problem for a memory one. Running inline applies backpressure to the
     * consumer, which is the only place backpressure can do any good — and
     * {@code max.poll.interval.ms} is 15 minutes against a 60-second run, so it is
     * safe.
     */
    private int workerQueueCapacity = 8;

    /**
     * How many completed runs to keep queryable in memory.
     *
     * <p>Bounds the REST status endpoint. The design service is the durable record;
     * this is a convenience for an operator asking "what happened to simulation X",
     * not a store of record.
     */
    private int retainedRuns = 200;
}
