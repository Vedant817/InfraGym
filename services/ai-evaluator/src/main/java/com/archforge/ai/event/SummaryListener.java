package com.archforge.ai.event;

import com.archforge.ai.config.EvaluatorProperties;
import com.archforge.ai.service.ArchitectureEvaluationService;
import com.archforge.contracts.event.SimulationSummaryEvent;
import com.archforge.messaging.KafkaTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Triggers an evaluation when a run is actually over.
 *
 * <p><b>Why this consumes {@code aggregated.summary} and not {@code aggregated.metrics}.</b>
 * The previous implementation counted messages: it buffered per simulation and fired when
 * the buffer reached five. That is wrong in three separate ways, all of them producing a
 * confident answer to a question nobody asked.
 *
 * <ul>
 *   <li><b>It fires mid-run.</b> Five aggregates arrive well before a 60-tick run ends, so
 *       the evaluation graded the first ten seconds of a workload whose peak load is at
 *       second thirty.</li>
 *   <li><b>It never fires for a small graph.</b> A two-node design produces fewer than five
 *       metric messages, so the buffer never reached the threshold and the submission sat
 *       unevaluated forever with no error anywhere.</li>
 *   <li><b>It could fire several times.</b> The buffer was cleared at the threshold and
 *       refilled, so a longer run produced a second evaluation from the next five messages,
 *       overwriting the first.</li>
 * </ul>
 *
 * <p>{@code aggregated.summary} is the run's terminal roll-up, published once, by the
 * component that already had to solve the "is this run finished" question correctly. Waiting
 * for it means the evaluation sees the whole run by construction.
 *
 * <p><b>Why unreadable records are thrown rather than caught.</b> The old listener caught
 * every exception and acknowledged anyway, so a payload this service could not read was
 * committed and gone: the submission was left unevaluated with a green health check and no
 * record of why. Throwing lets the shared error handler retry and then dead-letter it, which
 * is the only way an operator finds out the producer and consumer disagree.
 */
@Slf4j
@Component
public class SummaryListener {

    private final ObjectMapper objectMapper;
    private final ArchitectureEvaluationService evaluationService;
    private final EvaluatorProperties properties;
    private final KafkaTopics topics;
    private final MeterRegistry meterRegistry;

    /**
     * Simulations already evaluated, so a redelivered summary does not evaluate twice.
     *
     * <p>Idempotency has to be explicit here because the input is at-least-once: a consumer
     * rebalance between the evaluation and the offset commit replays the record. Two
     * evaluations of one run means two scores on the design, and a reader cannot tell which
     * one they are looking at.
     */
    private final Map<String, Boolean> evaluated = new ConcurrentHashMap<>();

    private final Map<String, Long> evaluatedAt = new ConcurrentHashMap<>();

    public SummaryListener(ObjectMapper objectMapper,
                           ArchitectureEvaluationService evaluationService,
                           EvaluatorProperties properties,
                           KafkaTopics topics,
                           MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.evaluationService = evaluationService;
        this.properties = properties;
        this.topics = topics;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "${archforge.kafka.topics.aggregated-summary:aggregated.summary}",
            groupId = "${archforge.evaluator.consumer-group:ai-evaluator-group}",
            containerFactory = "archforgeKafkaListenerContainerFactory"
    )
    public void onSummary(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        SimulationSummaryEvent summary;
        try {
            summary = objectMapper.readValue(payload, SimulationSummaryEvent.class);
        } catch (Exception e) {
            meterRegistry.counter("evaluator.summary.unreadable").increment();
            throw new IllegalArgumentException(
                    "Unreadable simulation summary: " + e.getMessage(), e);
        }
        if (summary == null || summary.simulationId() == null || summary.simulationId().isBlank()) {
            meterRegistry.counter("evaluator.summary.unidentified").increment();
            throw new IllegalArgumentException("Simulation summary carries no simulationId");
        }

        if (evaluated.putIfAbsent(summary.simulationId(), Boolean.TRUE) != null) {
            meterRegistry.counter("evaluator.summary.duplicate").increment();
            log.info("Simulation {} was already evaluated; ignoring the redelivered summary",
                    summary.simulationId());
            return;
        }
        evaluatedAt.put(summary.simulationId(), System.currentTimeMillis());

        meterRegistry.counter("evaluator.summary.received").increment();
        evaluationService.evaluate(summary);
    }

    /**
     * Forgets evaluations old enough that no redelivery can follow them.
     *
     * <p>One entry per run is small, but a service that runs for months would accumulate
     * them without limit. A generous retention -- far longer than any plausible consumer
     * lag or rebalance -- means a replayed summary is always still recognised.
     */
    public int sweepEvaluated(long olderThanMillis) {
        long cutoff = System.currentTimeMillis() - olderThanMillis;
        var expired = evaluatedAt.entrySet().stream()
                .filter(entry -> entry.getValue() < cutoff)
                .map(Map.Entry::getKey)
                .toList();
        expired.forEach(id -> {
            evaluated.remove(id);
            evaluatedAt.remove(id);
        });
        return expired.size();
    }

    public int evaluatedCount() {
        return evaluated.size();
    }

    String summaryTopic() {
        return topics.aggregatedSummary();
    }

    String consumerGroup() {
        return properties.getConsumerGroup();
    }
}