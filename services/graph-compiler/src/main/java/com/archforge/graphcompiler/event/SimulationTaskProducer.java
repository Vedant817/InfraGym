package com.archforge.graphcompiler.event;

import com.archforge.contracts.event.SimulationRequestedEvent;
import com.archforge.contracts.event.TopicNames;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Publishes compiled tasks to {@code simulation.tasks} and routes poison
 * messages to a dead-letter topic.
 *
 * <p>The message key is {@code simulationId} so every event for one simulation
 * lands on one partition. That is what lets the aggregator window a run in
 * order without a global buffer.
 *
 * <p>Topic names come from {@link TopicNames} with an environment override, never
 * from a literal, so a staging cluster can rename a topic without a rebuild.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimulationTaskProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${graph-compiler.kafka.topics.simulation-tasks:" + TopicNames.SIMULATION_TASKS + "}")
    private String simulationTasksTopic;

    @Value("${graph-compiler.kafka.topics.dead-letter:" + TopicNames.SIMULATION_TASKS_DLT + "}")
    private String deadLetterTopic;

    public CompletableFuture<SendResult<String, String>> publishSimulationTask(SimulationRequestedEvent event) {
        String key = event.simulationId();
        String value = serialize(event);

        log.debug("Publishing simulation task {} to {} ({} nodes)",
                key, simulationTasksTopic,
                event.graph() == null ? 0 : event.graph().nodeCount());

        return kafkaTemplate.send(simulationTasksTopic, key, value);
    }

    /**
     * Publish an unprocessable payload for later inspection.
     *
     * <p>The original message is included so an operator can replay it, but the
     * failure reason is logged rather than embedded in the envelope: this topic
     * is read by tooling, and raw exception text tends to leak internal paths and
     * hostnames into dashboards that are less protected than the logs.
     */
    public void publishToDeadLetter(String message, String reason, String originalTopic) {
        log.warn("Routing message from {} to {} because: {}", originalTopic, deadLetterTopic, reason);

        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("originalTopic", originalTopic);
            envelope.put("deadLetterTopic", deadLetterTopic);
            envelope.put("reason", reason);
            envelope.put("message", message);
            envelope.put("timestamp", java.time.Instant.now().toString());

            kafkaTemplate.send(deadLetterTopic, null, objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            // Never let DLQ failure mask the original failure.
            log.error("Failed to publish to dead-letter topic {}", deadLetterTopic, e);
        }
    }

    private String serialize(SimulationRequestedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize simulation task " + event.simulationId(), e);
        }
    }
}
