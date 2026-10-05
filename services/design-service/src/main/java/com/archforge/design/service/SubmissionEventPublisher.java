package com.archforge.design.service;

import com.archforge.contracts.event.DesignSubmittedEvent;
import com.archforge.contracts.event.SubmissionLifecycleEvent;
import com.archforge.contracts.lifecycle.SubmissionStatus;
import java.util.Map;
import com.archforge.messaging.KafkaTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Publishes a submission to {@code design.submissions}.
 *
 * <p>Waits for the broker rather than firing and forgetting. This is the pipeline's entry
 * point: a submission that is accepted and then silently lost leaves a user watching a row
 * that never progresses, with no error anywhere and nothing to retry. An unacknowledged send
 * is a submission that did not happen, and the caller needs to be able to say so.
 *
 * <p>Keyed by {@code submissionId}. Every later event refers back to that id, so keying by
 * it puts a submission's whole history -- submission, compilation, simulation, aggregation,
 * evaluation -- on one partition and in order. A consumer that has to reassemble a run from
 * three topics will eventually get the order wrong.
 */
@Slf4j
@Component
public class SubmissionEventPublisher {

    /** How long a publish may take before it is treated as failed. */
    static final int AWAIT_TIMEOUT_SECONDS = 15;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaTopics topics;
    private final MeterRegistry meterRegistry;

    public SubmissionEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                    ObjectMapper objectMapper,
                                    KafkaTopics topics,
                                    MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topics = topics;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Records that this service accepted the submission.
     *
     * <p>Without it the submission's history begins at whatever the next stage happened to be
     * -- {@code validating}, say -- and the event that explains why a submission is sitting at
     * {@code received} is missing from its own log. A user watching sees a status with no
     * event behind it and no way to tell "queued" from "lost".
     *
     * <p>Recorded rather than merely returned, so it lands in the same append-only log as every
     * other transition and is visible through the same endpoint. It carries the stage only;
     * the submission row holds the detail, and duplicating it here would create a second
     * place for the two to disagree.
     */
    public void accepted(java.util.UUID submissionId, java.util.UUID designId,
                         java.util.UUID simulationId, String userId, String scenario,
                         String message) {
        SubmissionLifecycleEvent event = new SubmissionLifecycleEvent(
                java.util.UUID.randomUUID().toString(),
                submissionId.toString(),
                submissionId.toString(),
                simulationId == null ? null : simulationId.toString(),
                userId,
                designId == null ? null : designId.toString(),
                SubmissionLifecycleEvent.STAGE_GATEWAY,
                SubmissionStatus.RECEIVED,
                message,
                Map.of("scenario", scenario == null ? "normal" : scenario),
                java.time.Instant.now());
        send(topics.submissionLifecycle(), event, submissionId.toString());
    }

    private void send(String topic, SubmissionLifecycleEvent event, String key) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            meterRegistry.counter("design.lifecycle.serialize_failed").increment();
            log.error("Failed to serialise lifecycle event for submission {}", key, e);
            return;
        }
        try {
            kafkaTemplate.send(topic, key, json).get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            meterRegistry.counter("design.lifecycle.published", "stage", event.stage()).increment();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            meterRegistry.counter("design.lifecycle.publish_failed").increment();
            log.error("Interrupted publishing lifecycle event for submission {}", key);
        } catch (Exception e) {
            // Not fatal. The submission row already exists at RECEIVED, so a missing progress
            // event costs the reader one line of history rather than the submission itself --
            // and a client that polls still sees the status.
            meterRegistry.counter("design.lifecycle.publish_failed").increment();
            log.error("Failed to publish lifecycle event for submission {} to {}: {}",
                    key, topic, e.getMessage());
        }
    }

    /**
     * @return true when the broker acknowledged it. A false return does not roll back the
     *         submission row -- it means the row exists and never started, which is a state a
     *         user can see and retry, and is strictly better than an error page with nothing
     *         recorded.
     */
    public boolean publish(DesignSubmittedEvent event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            meterRegistry.counter("design.submission.serialize_failed").increment();
            log.error("Failed to serialise submission {}", event.submissionId(), e);
            return false;
        }
        try {
            CompletableFuture<?> future = kafkaTemplate.send(
                    topics.designSubmissions(), event.submissionId(), json);
            future.get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            meterRegistry.counter("design.submission.published").increment();
            log.info("Published submission {} for design {} ({} nodes, {})",
                    event.submissionId(), event.designId(), event.topology().getNodes().size(),
                    event.scenario());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            meterRegistry.counter("design.submission.publish_failed").increment();
            log.error("Interrupted publishing submission {}", event.submissionId());
            return false;
        } catch (Exception e) {
            meterRegistry.counter("design.submission.publish_failed").increment();
            log.error("Failed to publish submission {} to {}: {}",
                    event.submissionId(), topics.designSubmissions(), e.getMessage());
            return false;
        }
    }
}