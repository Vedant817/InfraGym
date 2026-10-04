package com.archforge.simulation.event;

import com.archforge.contracts.event.SimulationLifecycleEvent;
import com.archforge.contracts.event.SimulationRequestedEvent;
import com.archforge.contracts.event.SubmissionLifecycleEvent;
import com.archforge.contracts.lifecycle.SimulationStatus;
import com.archforge.contracts.lifecycle.SubmissionStatus;
import com.archforge.messaging.KafkaTopics;
import com.archforge.messaging.SubmissionLifecyclePublisher;
import com.archforge.simulation.service.SimulationOutcomeRegistry;
import com.archforge.simulation.service.SimulationRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumes {@code simulation.tasks} and hands each task to a worker.
 *
 * <h2>Why the handler returns before the run finishes</h2>
 * The container commits an offset once the handler returns. Executing the run inline
 * means a poll's worth of work (up to {@code max.poll.records}) runs serially on one
 * consumer thread; at roughly 90 seconds per record against an unraised
 * {@code max.poll.interval.ms} the consumer was evicted, rejoined, and re-read from its
 * last committed offset — running the same work forever. With the shared
 * {@code AckMode.RECORD} the fix is to return immediately and do the work elsewhere.
 *
 * <h2>The cost of that, stated plainly</h2>
 * Handing off and returning commits the offset before the run completes, so a process
 * killed mid-run loses that run: the submission stays at {@code SIMULATING} and no
 * telemetry is published. That is the price of not deadlocking the consumer, and it is
 * a trade rather than a free win. It is mitigated, not fixed:
 *
 * <ul>
 *   <li>the run is on the pool before the handler returns, so it survives a rebalance
 *       and a broker hiccup;</li>
 *   <li>{@code setWaitForTasksToCompleteOnShutdown} lets in-flight runs finish on a
 *       graceful shutdown, which is how the service is actually stopped;</li>
 *   <li>any failure inside the run publishes a terminal lifecycle event and a
 *       {@code FAILED} submission transition, so a lost run is visible rather than
 *       silent — except on a hard kill, which is what {@code /actuator/health} and a
 *       restart are for.</li>
 * </ul>
 *
 * <p>Strict at-least-once execution needs manual acknowledgement with a deferred ack,
 * which the shared container configuration deliberately does not use.
 *
 * <h2>Failure classification</h2>
 * <ul>
 *   <li><b>Unparseable</b> — throws {@link IllegalArgumentException}; the shared error
 *       handler treats it as non-retryable and dead-letters it with the reason.</li>
 *   <li><b>No usable task</b> — no graph, no simulation id, unresolvable scenario:
 *       rejected with a lifecycle event and swallowed. A malformed record is not an
 *       infrastructure failure, and an operator's dead-letter queue is the wrong place
 *       for someone's bad input.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimulationTaskConsumer {

    /** Accepted work, so a redelivery while the first attempt runs is skipped. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private final ObjectMapper objectMapper;
    private final SimulationRunService runService;
    private final SimulationOutcomeRegistry outcomeRegistry;
    private final SimulationLifecyclePublisher lifecyclePublisher;
    private final SubmissionLifecyclePublisher submissionPublisher;
    private final KafkaTopics topics;
    private final TaskExecutor simulationExecutor;
    private final MeterRegistry meterRegistry;

    @KafkaListener(
            topics = "${archforge.topics.simulation-tasks:simulation.tasks}",
            groupId = "${spring.kafka.consumer.group-id:simulation-engine-group}",
            containerFactory = "archforgeKafkaListenerContainerFactory",
            concurrency = "${archforge.kafka.listener.concurrency:2}",
            autoStartup = "${archforge.kafka.enabled:true}"
    )
    public void consumeSimulationTask(@Payload String message) {
        SimulationRequestedEvent task = parseOrThrow(message);

        if (task.simulationId() == null || task.simulationId().isBlank()) {
            throw new IllegalArgumentException("Simulation task is missing simulationId");
        }
        if (task.graph() == null || task.graph().nodeCount() == 0) {
            reject(task, SimulationStatus.REJECTED, "Simulation task carries no compiled graph");
            return;
        }
        if (!inFlight.add(task.simulationId())) {
            log.warn("Simulation {} is already running; skipping redelivery", task.simulationId());
            return;
        }

        log.info("Accepted simulation {} (submission {}, scenario {}, {} nodes, {} edges, "
                        + "duration {}s, tick {}ms, seed {})",
                task.simulationId(), task.submissionId(), task.scenario(),
                task.graph().nodeCount(), task.graph().edgeCount(),
                task.durationSeconds(), task.tickMillis(), task.seed());

        meterRegistry.counter("simulation.accepted").increment();
        // execute() runs inline when the pool is saturated (CallerRunsPolicy), which is
        // how backpressure reaches the consumer thread instead of the heap.
        simulationExecutor.execute(() -> runAndCleanUp(task));
    }

    private void runAndCleanUp(SimulationRequestedEvent task) {
        try {
            runService.run(task);
        } catch (Throwable t) {
            // run() is contractually unable to throw. Catching anyway, because an
            // escaping exception here kills the worker thread silently and leaves the
            // submission stuck at SIMULATING with nothing to explain it.
            meterRegistry.counter("simulation.run.escaped_exception").increment();
            log.error("Simulation {} threw out of the run service", task.simulationId(), t);
            String message = t.getClass().getSimpleName() + ": " + t.getMessage();
            outcomeRegistry.recordFailure(task.simulationId(), task.submissionId(),
                    SimulationStatus.FAILED, message);
            publishLifecycle(task, SimulationStatus.FAILED, message);
            submissionPublisher.publish(SubmissionLifecycleEvent.of(
                    task.correlationId(), task.submissionId(), task.simulationId(),
                    task.userId(), null,
                    SubmissionLifecycleEvent.STAGE_SIMULATION, SubmissionStatus.FAILED,
                    "Simulation failed: " + message));
        } finally {
            inFlight.remove(task.simulationId());
        }
    }

    private SimulationRequestedEvent parseOrThrow(String message) {
        try {
            SimulationRequestedEvent task = objectMapper.readValue(message, SimulationRequestedEvent.class);
            if (task == null) {
                throw new IllegalArgumentException("Simulation task deserialised to null");
            }
            return task;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            // Wrapped so the shared error handler classifies it as non-retryable and
            // routes it straight to the dead-letter topic with the reason attached.
            throw new IllegalArgumentException("Unparseable simulation task: " + e.getMessage(), e);
        }
    }

    private void reject(SimulationRequestedEvent task, SimulationStatus status, String message) {
        log.warn("Rejecting simulation {}: {}", task.simulationId(), message);
        publishLifecycle(task, status, message);
        submissionPublisher.publish(SubmissionLifecycleEvent.of(
                task.correlationId(), task.submissionId(), task.simulationId(),
                task.userId(), null,
                SubmissionLifecycleEvent.STAGE_SIMULATION, SubmissionStatus.FAILED, message));
        outcomeRegistry.recordFailure(task.simulationId(), task.submissionId(), status, message);
        meterRegistry.counter("simulation.rejected").increment();
    }

    private void publishLifecycle(SimulationRequestedEvent task,
                                  SimulationStatus status,
                                  String message) {
        if (!lifecyclePublisher.publish(topics.simulationLifecycle(),
                SimulationLifecycleEvent.of(task.correlationId(), task.simulationId(),
                        task.submissionId(), task.scenarioType(), status, message,
                        0, task.tickCount(), task.graph()))) {
            log.error("Failed to publish {} lifecycle for simulation {}",
                    status, task.simulationId());
        }
    }
}
