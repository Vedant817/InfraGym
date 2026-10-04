package com.archforge.graphcompiler.event;

import com.archforge.contracts.event.DesignSubmittedEvent;
import com.archforge.contracts.event.SubmissionLifecycleEvent;
import com.archforge.contracts.lifecycle.SubmissionStatus;
import com.archforge.messaging.SubmissionLifecyclePublisher;
import com.archforge.graphcompiler.exception.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;


/**
 * Consumes {@code design.submissions} and compiles each design.
 *
 * <p>No {@code Acknowledgment} parameter on purpose. The container commits an
 * offset once the handler returns, and {@code DefaultErrorHandler} finishes its
 * retries and dead-letters before that happens. When this class acknowledged
 * explicitly, any handler that threw before acknowledging left the offset
 * uncommitted <em>even after the record had been dead-lettered</em> — so the same
 * poison message came back on every poll, forever.
 *
 * <p>Failure classification is now expressed by whether the handler throws:
 * <ul>
 *   <li><b>Unparseable</b> — throws {@link IllegalArgumentException}; retried, then
 *       dead-lettered. Retrying cannot help, but the error handler is what gets it
 *       to the DLQ, so the throw is the mechanism rather than a hand-rolled DLQ
 *       write that could itself fail.</li>
 *   <li><b>Validation rejected</b> — deterministic; the user's topology is wrong,
 *       not our infrastructure. Marked {@code REJECTED} so the UI can show the
 *       errors, then swallowed so a legitimate "you drew a cycle" does not land in
 *       an operator's dead-letter queue.</li>
 *   <li><b>Transient</b> — propagates; retried with exponential backoff, then
 *       dead-lettered.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DesignSubmissionConsumer {

    private final ObjectMapper objectMapper;
    private final CompilationService compilationService;
    private final SubmissionLifecyclePublisher lifecyclePublisher;

    @Value("${archforge.kafka.enabled:true}")
    private boolean kafkaEnabled;

    /**
     * Suppresses recompilation of a redelivery that arrives while the first attempt is
     * still running.
     *
     * <p>An optimisation, not a correctness mechanism: correctness comes from the
     * idempotent producer plus record-level acknowledgement. Entries are added on entry
     * to the handler and removed in {@code finally}, so the map holds only genuinely
     * in-progress ids and cannot grow without bound — which matters, because the earlier
     * TTL cache was the wrong structure for a set that is always emptied.
     *
     * <p>{@code putIfAbsent} is atomic, so two concurrent consumers cannot both win.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> inFlight =
            new java.util.concurrent.ConcurrentHashMap<>();

    @KafkaListener(
            topics = "${archforge.topics.design-submissions:design.submissions}",
            groupId = "${spring.kafka.consumer.group-id:graph-compiler-group}",
            containerFactory = "archforgeKafkaListenerContainerFactory",
            concurrency = "${archforge.kafka.listener.concurrency:2}",
            autoStartup = "${archforge.kafka.enabled:true}"
    )
    public void consumeDesignSubmission(@Payload String message) {
        DesignSubmittedEvent event = parseOrThrow(message);

        if (event.submissionId() == null || event.submissionId().isBlank()) {
            throw new IllegalArgumentException("Design submission is missing submissionId");
        }
        if (event.topology() == null || event.topology().nodeCount() == 0) {
            reject(event, "Design contains no nodes.",
                    List.of("Design contains no nodes. Add at least one component before submitting."));
            return;
        }
        if (inFlight.putIfAbsent(event.submissionId(), Boolean.TRUE) != null) {
            log.warn("Submission {} is already being compiled; skipping redelivery", event.submissionId());
            return;
        }
        log.info("Compiling submission {} (scenario={}, nodes={}, edges={})",
                event.submissionId(), event.scenario(),
                event.topology().nodeCount(), event.topology().edgeCount());

        lifecyclePublisher.publish(SubmissionLifecycleEvent.of(
                event.correlationId(), event.submissionId(), null, event.userId(), event.designId(),
                SubmissionLifecycleEvent.STAGE_COMPILER, SubmissionStatus.VALIDATING,
                "Validating topology"));

        try {
            var task = compilationService.compileAndPublish(event);

            lifecyclePublisher.publish(SubmissionLifecycleEvent.withDetails(
                    event.correlationId(), event.submissionId(), task.simulationId(),
                    event.userId(), event.designId(),
                    SubmissionLifecycleEvent.STAGE_COMPILER, SubmissionStatus.COMPILED,
                    "Topology compiled to a DAG",
                    Map.of("nodeCount", task.graph().nodeCount(),
                           "edgeCount", task.graph().edgeCount(),
                           "warnings", task.graph().getWarnings())));

            log.info("Processed submission {} -> simulation {}", event.submissionId(), task.simulationId());

        } catch (ValidationException e) {
            reject(event, "Topology failed validation", e.getErrors());
        } finally {
            inFlight.remove(event.submissionId());
        }
    }

    private DesignSubmittedEvent parseOrThrow(String message) {
        try {
            DesignSubmittedEvent event = objectMapper.readValue(message, DesignSubmittedEvent.class);
            if (event == null) {
                throw new IllegalArgumentException("Design submission deserialised to null");
            }
            return event;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            // Wrapped so DefaultErrorHandler classifies it as non-retryable and
            // routes it straight to the dead-letter topic with the reason attached.
            throw new IllegalArgumentException("Unparseable design submission: " + e.getMessage(), e);
        }
    }

    private void reject(DesignSubmittedEvent event, String message, List<String> errors) {
        log.warn("Submission {} rejected: {} ({})", event.submissionId(), message, errors);
        lifecyclePublisher.publish(SubmissionLifecycleEvent.withDetails(
                event.correlationId(), event.submissionId(), null, event.userId(), event.designId(),
                SubmissionLifecycleEvent.STAGE_COMPILER, SubmissionStatus.REJECTED, message,
                Map.of("errors", errors == null ? List.of() : errors)));
    }
}
