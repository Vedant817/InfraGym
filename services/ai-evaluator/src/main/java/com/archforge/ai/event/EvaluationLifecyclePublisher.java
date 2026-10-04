package com.archforge.ai.event;

import com.archforge.contracts.event.EvaluationLifecycleEvent;
import com.archforge.contracts.event.SimulationSummaryEvent;
import com.archforge.messaging.KafkaTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Says what the evaluator is doing while it does it.
 *
 * <p>The contract keeps {@code evaluation.lifecycle} separate from the result precisely so a
 * user watching a run sees "evaluating." the moment the simulation finishes, rather than
 * after the model has already returned. The previous service published no lifecycle events
 * at all, so a run went from "simulating" straight to a blank screen with no way to tell a
 * slow evaluation from a stuck one.
 *
 * <p>Publishes are synchronous and bounded, unlike the submission-progress publisher. This
 * is not a high-volume progress stream and a reader is waiting on it: an event that is never
 * acknowledged is an event the UI never shows.
 */
@Slf4j
@Component
public class EvaluationLifecyclePublisher {

    /** How long a lifecycle publish may take before it is treated as lost. */
    static final int AWAIT_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaTopics topics;
    private final MeterRegistry meterRegistry;

    public EvaluationLifecyclePublisher(KafkaTemplate<String, String> kafkaTemplate,
                                        ObjectMapper objectMapper,
                                        KafkaTopics topics,
                                        MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topics = topics;
        this.meterRegistry = meterRegistry;
    }

    public void started(SimulationSummaryEvent summary, String evaluationId) {
        publish(summary, evaluationId, EvaluationLifecycleEvent.STATUS_STARTED,
                String.format(java.util.Locale.ROOT,
                        "Evaluating %d component(s) across %d tick window(s).",
                        summary.nodeSummaries().size(), summary.windowAggregates().size()));
    }

    public void completed(SimulationSummaryEvent summary, String evaluationId, boolean degraded,
                           String reason) {
        publish(summary, evaluationId, EvaluationLifecycleEvent.STATUS_COMPLETED,
                degraded
                        ? "Evaluation complete (degraded: " + reason + ")."
                        : "Evaluation complete.");
    }

    public void degraded(SimulationSummaryEvent summary, String evaluationId, String reason) {
        publish(summary, evaluationId, EvaluationLifecycleEvent.STATUS_DEGRADED, reason);
    }

    public void failed(SimulationSummaryEvent summary, String evaluationId, String reason) {
        publish(summary, evaluationId, EvaluationLifecycleEvent.STATUS_FAILED, reason);
    }

    private void publish(SimulationSummaryEvent summary, String evaluationId,
                         String status, String message) {
        EvaluationLifecycleEvent event = EvaluationLifecycleEvent.of(
                summary.correlationId(), evaluationId, summary.simulationId(),
                summary.submissionId(), status, message);
        send(topics.evaluationLifecycle(), event, evaluationId);
    }

    /**
     * Sends one lifecycle event and waits for the broker.
     *
     * <p>Keyed by {@code submissionId} rather than {@code simulationId}: this is a
     * user-facing progress stream, and a reader following a submission wants every stage of
     * it in order on one partition. Keying by simulation id would scatter a single
     * submission's stages across partitions in an order the reader cannot reconstruct.
     */
    private void send(String topic, EvaluationLifecycleEvent event, String evaluationId) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            meterRegistry.counter("evaluator.lifecycle.serialize_failed").increment();
            log.error("Failed to serialise evaluation lifecycle event {}", evaluationId, e);
            return;
        }
        try {
            CompletableFuture<?> future = kafkaTemplate.send(topic, event.submissionId(), json);
            future.get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            meterRegistry.counter("evaluator.lifecycle.published", "status", event.status())
                    .increment();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            meterRegistry.counter("evaluator.lifecycle.publish_failed").increment();
            log.error("Interrupted publishing evaluation lifecycle {}", evaluationId);
        } catch (Exception e) {
            meterRegistry.counter("evaluator.lifecycle.publish_failed").increment();
            // Not fatal. The evaluation still happens and its result still lands; a missing
            // progress tick is a worse outcome than a slightly noisy log.
            log.error("Failed to publish evaluation lifecycle {} to {}: {}",
                    evaluationId, topic, e.getMessage());
        }
    }
}