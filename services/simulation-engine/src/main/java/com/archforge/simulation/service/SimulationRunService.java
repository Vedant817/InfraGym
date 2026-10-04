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
import com.archforge.simulation.engine.InvalidGraphException;
import com.archforge.simulation.engine.NodeReport;
import com.archforge.simulation.engine.SimulationAbandonedException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

        // Validate before announcing anything.
        //
        // Both transitions below are claims: QUEUED is what the aggregator opens a
        // window on, and SIMULATING is what the user sees on a progress bar. Emitting
        // them first meant a task rejected one line later had opened a window and moved
        // a user's progress bar, then reported REJECTED / FAILED. Nothing read those
        // claims back, so it was survivable — but a transition that describes work which
        // then does not happen is a lie with a timestamp on it.
        try {
            validate(task);
        } catch (Exception e) {
            fail(task, e instanceof ValidationException
                            ? SimulationStatus.REJECTED : SimulationStatus.FAILED,
                    e.getMessage(), startedAt, sample);
            return null;
        }

        publishLifecycle(task, SimulationStatus.QUEUED, "Simulation accepted", 0, task.tickCount());
        publishSubmission(task, SubmissionStatus.SIMULATING,
                "Simulating " + task.scenarioType().getDisplayName().toLowerCase() + " for "
                        + task.durationSeconds() + "s");

        try {
            WorkloadProfile workload = workloadGenerator.generate(
                    task.graph(), task.scenarioType(), task.durationSeconds());
            double nominal = workloadGenerator.nominalCapacityOf(task.graph());
            long ticks = tickBudget(task, workload);
            List<FailureInjector.Failure> failures = failureInjector.plan(
                    task.graph(), task.scenarioType(), task.seed(), ticks);

            publishLifecycle(task, SimulationStatus.RUNNING,
                    "Executing " + ticks + " ticks of scenario " + task.scenarioType().getValue(),
                    0, ticks);

            // The budget is enforced inside the loop, not after it: checked afterwards it
            // bounded nothing. A run that overran had already finished.
            long deadlineNanos = System.nanoTime() + wallClockBudget.toNanos();
            DiscreteEventSimulator simulator = new DiscreteEventSimulator(
                    task.graph(), workload, failures, nominal, task.seed(), task.tickMillis());

            AtomicLong emitted = new AtomicLong();
            TickObserver observer = telemetryObserver(task, emitted);

            try {
                simulator.run(ticks, observer, deadlineNanos);
            } catch (SimulationAbandonedException e) {
                abandon(task, e, simulator.ticksExecuted(), emitted.get(), sample);
                return null;
            }

            SimulationOutcome outcome = toOutcome(task, workload, nominal, ticks, failures,
                    simulator, emitted.get(), startedAt);

            // The ordering that matters: every sample is on the broker before COMPLETED.
            // flush() is a real barrier -- it waits for samples the drain thread is
            // holding, not merely for an empty queue.
            telemetryPublisher.flush();
            long totalDropped = telemetryPublisher.droppedFor(task.simulationId());

            publishLifecycle(task, SimulationStatus.COMPLETED,
                    "Completed " + ticks + " ticks; worst P99 "
                            + Math.round(outcome.worstNodeP99Ms()) + " ms",
                    ticks, ticks, completeness(emitted.get(), totalDropped));
            releaseDropAccounting(task);

            if (totalDropped > 0) {
                // The run's own record has to say so, or a chart read from 0.2% of the
                // trace looks exactly like a complete one.
                outcome = outcome.withWarning(totalDropped
                        + " of " + emitted.get() + " telemetry samples were dropped; this trace "
                        + "is incomplete and any percentile derived from it is unreliable.");
            }

            outcomeRegistry.record(outcome);
            runsCompleted.incrementAndGet();
            sample.stop(meterRegistry.timer("simulation.run.duration", "outcome", "completed"));

            log.info("Simulation {} completed: {} ticks, {} nodes, worst P99 {} ms, "
                            + "{} offered / {} served / {} failed / {} still queued, "
                            + "wall clock {} ms{}",
                    task.simulationId(), ticks, outcome.nodeReports().size(),
                    Math.round(outcome.worstNodeP99Ms()),
                    Math.round(outcome.totalOffered()), Math.round(outcome.totalServed()),
                    Math.round(outcome.totalFailed()),
                    Math.round(outcome.residualBacklog()),
                    outcome.wallClock().toMillis(),
                    totalDropped > 0 ? ", " + totalDropped + " samples dropped" : "");
            return outcome;

        } catch (ValidationException e) {
            fail(task, SimulationStatus.REJECTED, e.getMessage(), startedAt, sample);
            return null;
        } catch (InvalidGraphException e) {
            // A malformed graph is a bad record, not a broken service. Reporting it as
            // FAILED told the consumer the engine was unhealthy and left the operator
            // chasing an infrastructure problem for a producer bug; the imported-but-
            // unused InvalidGraphException in this file was the tell that the mapping to
            // REJECTED was intended and never written.
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

        long tickCeiling = Math.max(1, properties.getMaxTicksPerRun());

        // A tick ceiling alone is not a bound. The engine emits 7-11 samples per node
        // per tick, so the worst legal combination the config accepts -- maxTicksPerRun
        // ticks of maxNodes nodes -- asks for 500 x 20000 x ~9 = 90 million samples into
        // a 200 000 buffer. At that point 0.22% of the trace survives and the run still
        // reports COMPLETED. Derived from the graph so the ceiling means something.
        int nodeCount = task.graph() == null ? 1 : Math.max(1, task.graph().nodeCount());
        long samplesPerTick = Math.max(1L, nodeCount * (long) properties.getMetricsPerNode());
        long sampleCeiling = Math.max(1L, properties.getMaxSamplesPerRun()
                / Math.max(1L, samplesPerTick));

        long ceiling = Math.max(1, Math.min(tickCeiling, sampleCeiling));
        if (requested > ceiling) {
            log.warn("Task {} asked for {} ticks; clamped to {} (tick ceiling {}, "
                            + "sample ceiling {} for {} nodes x ~{} metrics)",
                    task.simulationId(), requested, ceiling, tickCeiling, sampleCeiling,
                    nodeCount, properties.getMetricsPerNode());
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
                simulator.residualBacklog(),
                task.graph(), workload, startedAt, Instant.now());
    }

    private void fail(SimulationRequestedEvent task,
                      SimulationStatus status,
                      String message,
                      Instant startedAt,
                      Timer.Sample sample) {
        fail(task, status, message, 0, Map.of(), startedAt, sample);
    }

    /**
     * Terminal handling for a run that did not complete.
     *
     * <p>{@code ticksExecuted} and {@code details} are what distinguish an abandoned run
     * from a rejected one. Both used to publish {@code tickCount = 0} against a
     * populated {@code totalTicks}, so a consumer computing progress as
     * {@code tickCount / totalTicks} divided by a known zero — and a user could not tell
     * a run that produced 40 ticks of real data from one that produced none.
     */
    private void fail(SimulationRequestedEvent task,
                      SimulationStatus status,
                      String message,
                      long ticksExecuted,
                      Map<String, Object> details,
                      Instant startedAt,
                      Timer.Sample sample) {
        runsFailed.incrementAndGet();
        sample.stop(meterRegistry.timer("simulation.run.duration", "outcome", status.getValue()));
        log.error("Simulation {} finished as {} after {} tick(s): {}",
                task.simulationId(), status, ticksExecuted, message);

        // Flush what was produced before failing: a partial trace plus an explicit
        // terminal status beats a silently absent one. The flush is bounded, so this
        // cannot itself hang the worker.
        //
        // Skipped when the process has nothing outstanding at all, which is the case for a
        // task rejected before it produced a single sample. Note the guard is
        // process-wide, not per-run: flush() is a global barrier, so with more than one
        // worker thread a rejected task can still wait on another run's telemetry. That is
        // accepted rather than solved -- a per-run barrier needs per-run queues, which
        // would cost the single-partition-per-run ordering the keying exists to provide.
        if (telemetryPublisher.inFlightSamples() > 0) {
            telemetryPublisher.flush();
        }
        releaseDropAccounting(task);

        long totalTicks = Math.max(1, task.tickCount());
        Map<String, Object> allDetails = new LinkedHashMap<>(details);
        allDetails.put("ticksExecuted", ticksExecuted);
        allDetails.put("ticksRequested", task.tickCount());

        publishLifecycle(task, status, message, ticksExecuted, totalTicks, allDetails);
        publishSubmission(task, SubmissionStatus.FAILED, "Simulation failed: " + message);
        outcomeRegistry.recordFailure(task.simulationId(), task.submissionId(), status, message);
    }

    /**
     * Whether a run's trace can be trusted, and by how much it is missing.
     *
     * <p>Carried on the terminal lifecycle event so a consumer can tell a complete trace
     * from a partial one <em>without</em> having to diff two topics and work it out. The
     * aggregator needs this to avoid publishing a percentile computed from 0.2% of the
     * samples as though it described the run.
     */
    private static Map<String, Object> completeness(long emitted, long dropped) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("telemetrySamplesEmitted", emitted);
        details.put("telemetrySamplesDropped", dropped);
        details.put("telemetryComplete", dropped == 0);
        return details;
    }

    /**
     * Drops the per-simulation telemetry accounting now that the run is terminal.
     *
     * <p>The run's totals are on its lifecycle event and on the retained outcome, so the
     * counter has no further reader. Without this the publisher's map grows one entry per
     * run for the life of the process, and it grows fastest exactly when the broker is
     * struggling — which is the worst possible time to leak.
     */
    private void releaseDropAccounting(SimulationRequestedEvent task) {
        telemetryPublisher.forgetDrops(task.simulationId());
    }

    /**
     * Publishes a lifecycle transition.
     *
     * <p>Not fatal when it fails — the run's telemetry is already on the wire — but
     * counted and logged, because a lost {@code COMPLETED} leaves the aggregator waiting
     * and the submission stuck at {@code AGGREGATING} with nothing to explain it.
     */
    private void publishLifecycle(SimulationRequestedEvent task,
                                  SimulationStatus status,
                                  String message,
                                  long tick,
                                  long totalTicks) {
        publishLifecycle(task, status, message, tick, totalTicks, Map.of());
    }

    private void publishLifecycle(SimulationRequestedEvent task,
                                  SimulationStatus status,
                                  String message,
                                  long tick,
                                  long totalTicks,
                                  Map<String, Object> details) {
        SimulationLifecycleEvent event = SimulationLifecycleEvent.of(
                task.correlationId(), task.simulationId(), task.submissionId(),
                task.scenarioType(), status, message, tick, totalTicks, task.graph(), details);
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

    /**
     * The run was abandoned part-way, and its partial trace should be kept.
     *
     * <p>Separate from a generic failure so the terminal event says how far it got. A run
     * abandoned at tick 40 of 60 has 40 ticks of real measurement behind it, and a user
     * told "failed" with no figures has strictly less than one told "abandoned at tick
     * 40".
     */
    private void abandon(SimulationRequestedEvent task,
                         SimulationAbandonedException cause,
                         long ticksExecuted,
                         long samplesEmitted,
                         Timer.Sample sample) {
        String message = cause.getMessage();
        log.error("Simulation {} abandoned after {} of {} ticks",
                task.simulationId(), ticksExecuted, task.tickCount());

        telemetryPublisher.flush();
        long dropped = telemetryPublisher.droppedFor(task.simulationId());

        // The sample count is passed, not the tick count. Both were called `emitted` at one
        // point, which put a tick count in under telemetrySamplesEmitted and made any
        // consumer computing a drop *fraction* from these two keys wrong by the node count
        // times the metrics per node.
        Map<String, Object> details = new LinkedHashMap<>(
                completeness(samplesEmitted, dropped));
        details.put("partialTraceTicks", ticksExecuted);
        details.put("telemetryComplete", false);

        runsFailed.incrementAndGet();
        // Tagged with the status, like every other terminal path. "abandoned" was a sixth
        // spelling of one outcome, so a dashboard filtering on outcome=timed_out showed
        // none of the runs it was meant to show.
        sample.stop(meterRegistry.timer("simulation.run.duration", "outcome",
                SimulationStatus.TIMED_OUT.getValue()));
        publishLifecycle(task, SimulationStatus.TIMED_OUT, message,
                ticksExecuted, Math.max(1, task.tickCount()), details);
        publishSubmission(task, SubmissionStatus.FAILED,
                "Simulation timed out after " + ticksExecuted + " of "
                        + task.tickCount() + " ticks: " + message);
        outcomeRegistry.recordFailure(task.simulationId(), task.submissionId(),
                SimulationStatus.TIMED_OUT, message);
        releaseDropAccounting(task);
    }

    /** A task that cannot be simulated at all: no graph, unresolvable scenario, too big. */
    static final class ValidationException extends RuntimeException {
        ValidationException(String message) {
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
