package com.archforge.graphcompiler.event;

import com.archforge.graphcompiler.schema.SimulationTaskEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class SimulationTaskProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${graph-compiler.kafka.topics.simulation-tasks:simulation.tasks}")
    private String simulationTasksTopic;

    @Value("${graph-compiler.kafka.topics.dead-letter:graph-compiler.dlt}")
    private String deadLetterTopic;

    public CompletableFuture<SendResult<String, String>> publishSimulationTask(SimulationTaskEvent event) {
        String key = event.getSimulationId();
        String value = serializeEvent(event);

        log.debug("Publishing simulation task: {} to topic: {}", event.getSimulationId(), simulationTasksTopic);

        return kafkaTemplate.send(simulationTasksTopic, key, value)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish simulation task: {}", event.getSimulationId(), ex);
                    } else {
                        log.debug("Published simulation task: {} to partition: {}", event.getSimulationId(), result.getRecordMetadata().partition());
                    }
                });
    }

    public void publishToDeadLetter(String message, String reason, String originalTopic) {
        log.warn("Publishing to dead letter queue from topic: {}, reason: {}", originalTopic, reason);

        try {
            Map<String, String> dlqMessage = new HashMap<>();
            dlqMessage.put("originalTopic", originalTopic);
            dlqMessage.put("reason", reason);
            dlqMessage.put("message", message);
            dlqMessage.put("timestamp", java.time.Instant.now().toString());

            String json = objectMapper.writeValueAsString(dlqMessage);

            kafkaTemplate.send(deadLetterTopic, json)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish to dead letter queue for topic: {}", originalTopic, ex);
                        } else {
                            log.debug("Published to dead letter queue: partition {}", result.getRecordMetadata().partition());
                        }
                    });
        } catch (Exception e) {
            log.error("Failed to serialize dead letter message for topic: {}", originalTopic, e);
        }
    }

    private String serializeEvent(SimulationTaskEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("Failed to serialize simulation task event", e);
            throw new RuntimeException("Failed to serialize simulation task event", e);
        }
    }
}
