package com.archforge.messaging;

import com.archforge.contracts.event.SubmissionLifecycleEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link SubmissionLifecycleEvent} so the design service can track a
 * submission's progress.
 *
 * <p>Fire-and-forget on purpose. This is a progress signal, not work: a lost
 * transition is superseded by the next stage's transition, and blocking the
 * caller's thread on a status write would couple stage latency to broker health.
 * Failures are counted, not thrown — a broker problem must surface as a metric,
 * not as a 500 to a user whose design compiled perfectly well.
 *
 * <p>Keyed by {@code submissionId} so every transition for one submission stays
 * ordered on a single partition. Without that, the design service could apply a
 * stale transition after a newer one and rewind the user's progress bar.
 */
@Slf4j
@Component
public class SubmissionLifecyclePublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTopics topics;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public SubmissionLifecyclePublisher(KafkaTemplate<String, String> kafkaTemplate,
                                        KafkaTopics topics,
                                        ObjectMapper objectMapper,
                                        MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public void publish(SubmissionLifecycleEvent event) {
        if (event == null || event.submissionId() == null) {
            log.warn("Refusing to publish a submission lifecycle event with no submissionId");
            return;
        }

        String topic = topics.submissionLifecycle();
        String stage = event.stage() == null ? "unknown" : event.stage();

        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            // Serialising our own contract failing is a code defect; surface it loudly.
            log.error("Failed to serialize lifecycle event for submission {}", event.submissionId(), e);
            meterRegistry.counter("lifecycle.publish.serialize_failed").increment();
            return;
        }

        kafkaTemplate.send(topic, event.submissionId(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        meterRegistry.counter("lifecycle.publish.failed", "stage", stage).increment();
                        log.warn("Failed to publish {} transition for submission {} to {}: {}",
                                event.status(), event.submissionId(), topic, ex.getMessage());
                    } else {
                        meterRegistry.counter("lifecycle.publish.success", "stage", stage).increment();
                    }
                });
    }
}
