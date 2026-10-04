package com.archforge.ai.service;

import com.archforge.ai.analysis.ArchitectureAnalysis;
import com.archforge.ai.config.EvaluatorProperties;
import com.archforge.contracts.event.ArchitectureEvaluationEvent;
import com.archforge.contracts.event.SimulationLifecycleEvent;
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
 * Publishes the evaluation result.
 *
 * <p>Waits for the broker, unlike the submission-progress publisher. This is not a progress
 * signal: an evaluation the design service never receives leaves a submission with a score
 * nobody can read, and the user has no way to tell that from a slow simulation.
 *
 * <p>Keyed by {@code submissionId}, so a submission's whole evaluation history sits on one
 * partition in order. Keying by simulation id would scatter a single design's runs across
 * partitions and give the reader an order they cannot reconstruct.
 */
@Slf4j
@Component
public class EvaluationResultPublisher {

    /** How long a publish may take before it is treated as failed. */
    static final int AWAIT_TIMEOUT_SECONDS = 15;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaTopics topics;
    private final MeterRegistry meterRegistry;

    public EvaluationResultPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                     ObjectMapper objectMapper,
                                     KafkaTopics topics,
                                     MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topics = topics;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Publishes the evaluation.
     *
     * @return true when the broker acknowledged it. A false return does not roll anything
     *         back -- the evaluation happened and the record is in the log of the DLT path if
     *         the send failed -- but it is counted and logged, because a silently lost
     *         evaluation is the same class of failure as a silently lost simulation.
     */
    public boolean publish(ArchitectureEvaluationEvent event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            meterRegistry.counter("evaluator.result.serialize_failed").increment();
            log.error("Failed to serialise evaluation {}", event.evaluationId(), e);
            return false;
        }
        try {
            CompletableFuture<?> future = kafkaTemplate.send(
                    topics.evaluationResults(), event.submissionId(), json);
            future.get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            meterRegistry.counter("evaluator.result.published", "verdict", event.verdict())
                    .increment();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            meterRegistry.counter("evaluator.result.publish_failed").increment();
            log.error("Interrupted publishing evaluation {}", event.evaluationId());
            return false;
        } catch (Exception e) {
            meterRegistry.counter("evaluator.result.publish_failed").increment();
            log.error("Failed to publish evaluation {} to {}: {}",
                    event.evaluationId(), topics.evaluationResults(), e.getMessage());
            return false;
        }
    }

    /**
     * The query used to retrieve reference architectures.
     *
     * <p>Derived from the graph rather than from the metrics: "what did this design look
     * like" is a better retrieval query than "p99 850". A reference library is indexed by
     * architecture, so a query describing components and topology matches the documents
     * written about them.
     */
    public static String retrievalQuery(SimulationSummaryEvent summary) {
        SimulationLifecycleEvent.ArchitectureDigest architecture = summary.architecture();
        StringBuilder query = new StringBuilder();
        if (architecture != null && architecture.nodes() != null) {
            architecture.nodes().stream()
                    .map(node -> node.type() + " " + node.label())
                    .distinct()
                    .forEach(text -> query.append(text).append(", "));
        }
        query.append("under ").append(summary.scenario()).append(" load");
        return query.toString();
    }
}