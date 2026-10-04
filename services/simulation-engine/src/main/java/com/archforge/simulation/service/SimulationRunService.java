package com.archforge.simulation.service;

import com.archforge.contracts.event.SimulationLifecycleEvent;
import com.archforge.contracts.event.SimulationRequestedEvent;
import com.archforge.contracts.event.SubmissionLifecycleEvent;
import com.archforge.contracts.event.TelemetryEvent;
import com.archforge.contracts.lifecycle.ScenarioType;
import com.archforge.contracts.lifecycle.SimulationStatus;
import com.archforge.contracts.lifecycle.SubmissionStatus;
import com.archforge.messaging.KafkaTopics;
import com.archforge.messaging.SubmissionLifecyclePublisher;
import com.archforge.simulation.config.SimulationProperties;
import com.archforge.simulation.engine.DiscreteEventSimulator;
import com.archforge.simulation.engine.NodeReport;
import com.archforge.simulation.engine.SimulationOutcome;
import com.archforge.simulation.engine.TickObserver;
import com.archforge.simulation.event.SimulationLifecyclePublisher;
import com.archforge.simulation.event.TelemetryPublisher;
import com.archforge.simulation.failure.FailureInjector;
import com.archforge.simulation.workload.WorkloadGenerator;
import com.archforge.simulation.workload.WorkloadProfile;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs one simulation end to end and announces what happened.
 *
 * <h2>Ordering is load-bearing</h2>
 * The sequence below is the contract with the rest of the pipeline, and getting it
 * wrong produces wrong numbers rather than an error:
 *
 * <ol>
 *   <li>{@code QUEUED} lifecycle — the aggregator opens a window for the run.</li>
 *   <li>{@code RUNNING} lifecycle — carries the architecture digest, so the aggregator
 *       can label every sample without a second lookup.</li>
 *   <li>telemetry — the run's measurements.</li>
 *   <li><b>flush telemetry and wait for acknowledgement.</b> Skipping this lets the
 *       aggregator see {@code COMPLETED} and summarise a partial trace, which is how
 *       evaluation used to fire on five messages' worth of data.</li>
 *   <li>{@code COMPLETED} lifecycle — the run is over.</li>
 * </ol>
 *
 * <p>The submission's own status advances in step, so a user watching a progress bar
 * sees {@code SIMULATING} while this runs and is not left on {@code COMPILED} for the
 * duration of a minute-long simulation.
 *
 * <h2>Nothing here is allowed to throw at the caller</h2>
 * The consumer hands off to a worker thread, so an escaping exception would kill the
 * thread silently and leave the submission stuck at {@code SIMULATING}. Every failure
 * is converted into a terminal lifecycle event and a {@code FAILED} submission
 * transition before it is logged.
 */
@Slf4j
@Service
public class SimulationRunService {

    private final WorkloadGenerator workloadGenerator;
    private final FailureInjector failureInjector;
    private final TelemetryPublisher telemetryPublisher;
    private final SubmissionLifecyclePublisher lifecyclePublisher;
    private final KafkaTopics topics;
    private final SimulationProperties properties;
    private final MeterRegistry meterRegistry;
    private final SimulationOutcomeRegistry outcomeRegistry;
    private final SimulationLifecyclePublisher simulationLifecyclePublisher;

    /** Wall-clock ceiling for a run, independent of the tick budget. */
    private final Duration wallClockBudget;

    private final AtomicLong runsCompleted = new AtomicLong();
    private final AtomicLong runsFailed = new AtomicLong();

    public SimulationRunService(WorkloadGenerator workloadGenerator,
                                FailureInjector failureInjector,
                                TelemetryPublisher telemetryPublisher,
                                SubmissionLifecyclePublisher lifecyclePublisher,
                                SimulationLifecyclePublisher simulationLifecyclePublisher,
                                KafkaTopics topics,
                                SimulationProperties properties,
                                MeterRegistry meterRegistry,
                                SimulationOutcomeRegistry outcomeRegistry) {
        this.workloadGenerator = workloadGenerator;
        this.failureInjector = failureInjector;
        this.telemetryPublisher = telemetryPublisher;
        this.lifecyclePublisher = lifecyclePublisher;
        this.simulationLifecyclePublisher = simulationLifecyclePublisher;
        this.topics = topics;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.outcomeRegistry = outcomeRegistry;
        // A generous ceiling: the tick budget already bounds the work, and this only
        // catches a pathological run (a JVM pause, a pathological graph) so it cannot
        // occupy a worker thread indefinitely.
        this.wallClockBudget = Duration.ofSeconds(
                Math.max(60, properties.getDefaultDurationSeconds() * 20));
    }

    /**
     * Executes one task. Never throws.
     *
     * @return the outcome on success, or {@code null} when the run failed. A null
     *         result with a published {@code FAILED} lifecycle event is the failure
     *         contract — the caller has nothing else to do about it.
     */
    public SimulationOutcome run(SimulationRequestedEvent task) {
        Timer.Sample sample = Timer.start(meterRegistry);
        Instant startedAt = Instant.now();

        publishLifecycle(task, SimulationStatus.QUEUED, "Simulation accepted", 0, task.tickCount());
        publishSubmission(task, SubmissionStatus.SIMULATING,
                "Simulating " + task.scenarioType().getDisplayName().toLowerCase() + " for "
                        + task.durationSeconds() + "s");

        try {
            validate(task);

            WorkloadProfile workload = workloadGenerator.generate(
                    task.graph(), task.scenarioType(), task.durationSeconds());
            double nominal = workloadGenerator.nominalCapacityOf(task.graph());
            long ticks = tickBudget(task, workload);
            List<FailureInjector.Failure> failures = failureInjector.plan(
                    task.graph(), task.scenarioType(), task.seed(), ticks);

            publishLifecycle(task, SimulationStatus.RUNNING,
                    "Executing " + ticks + " ticks of scenario " + task.scenarioType().getValue(),
                    0, ticks);

            DiscreteEventSimulator simulator = new DiscreteEventSimulator(
                    task.graph(), workload, failures, nominal, task.seed(), task.tickMillis());

            long deadline = System.nanoTime() + wallClockBudget.toNanos();
            AtomicLong emitted = new AtomicLong();
            TickObserver observer = telemetryObserver(task, emitted);

            simulator.run(ticks, observer);

            if (System.nanoTime() > deadline) {
                throw new SimulationTimeoutException(
                        "run exceeded its " + wallClockBudget.toSeconds() + "s wall-clock budget");
            }

            SimulationOutcome outcome = toOutcome(task, workload, nominal, ticks, failures,
                    simulator, emitted.get(), startedAt);

            // Ordering matters: every sample must be on the broker before COMPLETED.
            telemetryPublisher.flush();
            publishLifecycle(task, SimulationStatus.COMPLETED,
                    "Completed " + ticks + " ticks; worst P99 " + Math.round(outcome.worstNodeP99Ms())
                            + " ms",
                    ticks, ticks);
            outcomeRegistry.record(outcome);
            runsCompleted.incrementAndGet();
            sample.stop(meterRegistry.timer("simulation.run.duration", "outcome", "completed"));

            log.info("Simulation {} completed: {} ticks, {} nodes, worst P99 {} ms, "
                            + "{} offered / {} served / {} failed, wall clock {} ms",
                    task.simulationId(), ticks, outcome.nodeReports().size(),
                    Math.round(outcome.worstNodeP99Ms()),
                    Math.round(outcome.totalOffered()), Math.round(outcome.totalServed()),
                    Math.round(outcome.totalFailed()), outcome.wallClock().toMillis());
            return outcome;

        } catch (ValidationException e) {
            fail(task, SimulationStatus.REJECTED, e.getMessage(), startedAt, sample);
            return null;
        } catch (Exception e) {
            fail(task, SimulationStatus.FAILED,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), startedAt, sample);
            return null;
        }
    }

    /**
     * Turns one simulator observation into a telemetry event and enqueues it.
     *
     * <p>Built here rather than inside the simulator so the engine has no knowledge of
     * Kafka, the submission id, or the wire event type — and so a unit test can drive
     * the simulator with a recording observer and assert on the same samples.
     */
    private TickObserver telemetryObserver(SimulationRequestedEvent task, AtomicLong emitted) {
        return (node, state, metricName, value, tick, phase, failing) -> {
            TelemetryEvent event = TelemetryEvent.of(
                    task.simulationId(),
                    task.submissionId(),
                    node.getId(),
                    node.getType(),
                    metricName,
                    finite(value),
                    tick,
                    phase,
                    failing);
            telemetryPublisher.publish(event);
            emitted.incrementAndGet();
        };
    }

    /**
     * Replaces a non-finite value with something publishable.
     *
     * <p>A {@code NaN} or {@code +Infinity} in a telemetry payload is not a number a
     * consumer can chart or a percentile can absorb; it propagates into the aggregate
     * and makes every downstream average {@code NaN}. Zero is honest here because the
     * simulator never produces a non-finite sample for any other reason — the
     * infinities that used to reach the wire were the timed-out requests being counted
     * as infinitely slow responses, which no longer happens by construction.
     */
    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0;
    }

    /**
     * How many ticks to execute.
     *
     * <p>The profile's own length, bounded by the task's duration and by the
     * configured ceiling. The ceiling is the important part: the tick count arrives in
     * the record, and a record claiming a week-long run at a 100 ms tick is 6 million
     * ticks per node. Nothing enforces that anywhere else.
     */
    long tickBudget(SimulationRequestedEvent task, WorkloadProfile workload) {
        long byDuration = task.durationSeconds() * 1000L / Math.max(1, task.tickMillis());
        long byProfile = workload.totalDurationSeconds() * 1000L / Math.max(1, task.tickMillis());
        long requested = Math.max(1, Math.min(byDuration, byProfile));
        long ceiling = Math.max(1, properties.getMaxTicksPerRun());
        if (requested > ceiling) {
            log.warn("Task {} asked for {} ticks; clamped to the {} tick ceiling",
                    task.simulationId(), requested, ceiling);
        }
        return Math.min(requested, ceiling);
    }

    private void validate(SimulationRequestedEvent task) {
        if (task.graph() == null || task.graph().nodeCount() == 0) {
            throw new ValidationException("Simulation task carries no compiled graph");
        }
        if (task.graph().nodeCount() > properties.getMaxNodes()) {
            throw new ValidationException("Graph has " + task.graph().nodeCount()
                    + " nodes; the limit is " + properties.getMaxNodes());
        }
        if (task.simulationId() == null || task.simulationId().isBlank()) {
            throw new ValidationException("Simulation task carries no simulationId");
        }
        // Also fails fast on a payload that arrived as an unresolvable scenario string,
        // rather than silently degrading it to NORMAL and reporting a steady-state run
        // the user never asked for.
        ScenarioTypeValidation.validate(task);
    }

    private SimulationOutcome toOutcome(SimulationRequestedEvent task,
                                        WorkloadProfile workload,
                                        double nominal,
                                        long ticks,
                                        List<FailureInjector.Failure> failures,
                                        DiscreteEventSimulator simulator,
                                        long emittedSamples,
                                        Instant startedAt) {
        List<NodeReport> reports = simulator.states().values().stream()
                .map(NodeReport::from)
                .toList();

        List<String> warnings = new ArrayList<>(simulator.routingWarnings());
        if (task.graph().getWarnings() != null) {
            warnings.addAll(task.graph().getWarnings());
        }
        if (emittedSamples == 0) {
            warnings.add("The simulation emitted no telemetry samples; the run produced no measurements.");
        }

        return new SimulationOutcome(task.simulationId(), task.submissionId(), task.correlationId(),
                task.scenarioType(), task.seed(), nominal, ticks, task.tickMillis(),
                reports, failures, warnings,
                simulator.totalOffered(), simulator.totalServed(), simulator.totalFailed(),
                task.graph(), workload, startedAt, Instant.now());
    }

    private void fail(SimulationRequestedEvent task,
                      SimulationStatus status,
                      String message,
                      Instant startedAt,
                      Timer.Sample sample) {
        runsFailed.incrementAndGet();
        sample.stop(meterRegistry.timer("simulation.run.duration", "outcome", status.getValue()));
        log.error("Simulation {} finished as {}: {}", task.simulationId(), status, message);
        // Flush what was produced before the failure: a partial trace plus an explicit
        // FAILED beats a silently absent one.
        telemetryPublisher.flush();
        publishLifecycle(task, status, message, 0, task.tickCount());
        publishSubmission(task, SubmissionStatus.FAILED,
                "Simulation failed: " + message);
        outcomeRegistry.recordFailure(task.simulationId(), task.submissionId(), status, message);
    }

    /**
     * Publishes a lifecycle transition.
     *
     * <p>Not fatal when it fails — the run's telemetry is already on the wire — but
     * counted and logged, because a lost {@code COMPLETED} leaves the aggregator
     * waiting and the submission stuck at {@code AGGREGATING} with nothing to explain
     * it.
     */
    private void publishLifecycle(SimulationRequestedEvent task,
                                  SimulationStatus status,
                                  String message,
                                  long tick,
                                  long totalTicks) {
        SimulationLifecycleEvent event = SimulationLifecycleEvent.of(
                task.correlationId(), task.simulationId(), task.submissionId(),
                task.scenarioType(), status, message, tick, totalTicks, task.graph());
        if (!simulationLifecyclePublisher.publish(topics.simulationLifecycle(), event)) {
            log.error("Aggregator will not learn that simulation {} is {}: lifecycle publish failed",
                    task.simulationId(), status);
        }
    }

    private void publishSubmission(SimulationRequestedEvent task,
                                   SubmissionStatus status,
                                   String message) {
        lifecyclePublisher.publish(SubmissionLifecycleEvent.of(
                task.correlationId(), task.submissionId(), task.simulationId(),
                task.userId(), null,
                SubmissionLifecycleEvent.STAGE_SIMULATION, status, message));
    }

    public long runsCompleted() {
        return runsCompleted.get();
    }

    public long runsFailed() {
        return runsFailed.get();
    }

    /** Signals that the node declares no usable simulation characteristics. */
    static final class ValidationException extends RuntimeException {
        ValidationException(String message) {
            super(message);
        }
    }

    /** Signals that a run exceeded its wall-clock budget. */
    static final class SimulationTimeoutException extends RuntimeException {
        SimulationTimeoutException(String message) {
            super(message);
        }
    }

    /**
     * Resolves the scenario string strictly.
     *
     * <p>{@code SimulationRequestedEvent.scenarioType()} returns NORMAL for anything it
     * cannot parse. That is right for a default and wrong for an explicit request: a
     * typo'd {@code flashsale} would run a steady-state simulation and report it as
     * {@code flash_sale}, and the user would be shown a normal workload's numbers as
     * though they were the flash sale's. Every one of the nine scenarios used to
     * degrade exactly this way, because the field was never populated at all.
     */
    static final class ScenarioTypeValidation {

        private ScenarioTypeValidation() {
        }

        static void validate(SimulationRequestedEvent task) {
            String raw = task.scenario();
            if (raw == null || raw.isBlank()) {
                return;
            }
            if (ScenarioType.findByValue(raw) == null) {
                throw new ValidationException("Unknown scenario '" + raw
                        + "'. Valid scenarios: " + String.join(", ", ScenarioType.allValues()));
            }
        }
    }
}
