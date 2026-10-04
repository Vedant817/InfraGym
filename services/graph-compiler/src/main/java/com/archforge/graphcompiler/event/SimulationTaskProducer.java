package com.archforge.graphcompiler.event;

import com.archforge.contracts.event.SimulationRequestedEvent;
import com.archforge.messaging.KafkaTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Publishes compiled tasks to {@code simulation.tasks}.
 *
 * <p>The message key is {@code simulationId} so every event for one simulation lands on
 * one partition. That is what lets the aggregator window a run in order without a
 * global buffer.
 *
 * <p>Topic names are read from {@link KafkaTopics} and never from a literal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimulationTaskProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Injected, not {@code @Value}.
     *
     * <p>The two fields this replaced read {@code graph-compiler.kafka.topics.*}, a
     * namespace no {@code application.yml} in this repository ever defines. They
     * therefore always resolved to the hard-coded literal, while the engine's consumer
     * read {@code archforge.topics.simulation-tasks}, which <em>is</em> bridged to
     * {@code TOPIC_SIMULATION_TASKS}.
     *
     * <p>The consequence was silent and total. An operator setting
     * {@code TOPIC_SIMULATION_TASKS=sim.tasks.v2} on a staging cluster — exactly what the
     * topic catalogue promises is possible without a rebuild — got a compiler still
     * publishing to {@code simulation.tasks} and an engine consuming
     * {@code sim.tasks.v2}. Empty input topic, empty output topic, both services
     * healthy, no error anywhere.
     *
     * <p>{@link KafkaTopics} is the one place a topic name is read. A literal here
     * re-introduces the split in the one file whose entire purpose is to remove it.
     */
    private final KafkaTopics topics;

    public CompletableFuture<SendResult<String, String>> publishSimulationTask(SimulationRequestedEvent event) {
        String key = event.simulationId();
        String value = serialize(event);

        log.debug("Publishing simulation task {} to {} ({} nodes)",
                key, topics.simulationTasks(),
                event.graph() == null ? 0 : event.graph().nodeCount());

        return kafkaTemplate.send(topics.simulationTasks(), key, value);
    }

    /**
     * Publish an unprocessable payload for later inspection.
     *
     * <p><b>Deprecated, and no longer called.</b> Kept only so the reasoning is not lost.
     *
     * <p>This hand-rolled writer was the wrong mechanism twice over. It wrote to
     * {@code simulation.tasks.dlt} — a topic owned by the <em>simulation engine</em> —
     * while the compiler's own poison records go to {@code design.submissions.dlt} via
     * the container's error handler. So the two writers aimed at two topics in two
     * incompatible envelope shapes: this one emitted
     * {@code {originalTopic, deadLetterTopic, reason, message, timestamp}} while
     * {@code DeadLetterPublishingRecoverer} emits the raw payload plus
     * {@code KafkaHeaders.ORIGINAL_*} headers. A replay tool had to understand both, and
     * {@code getDLQInfo} advertised only this one's keys, so tooling built against that
     * response mis-handled every container-produced record.
     *
     * <p>Routing failures through the container's handler is the whole point of having
     * one: retries with backoff, then a dead-letter write that cannot itself fail
     * silently, in one place. {@link DeadLetterController} already reads both shapes, so
     * replay works for records from either path.
     */
    @Deprecated(forRemoval = true)
    public void publishToDeadLetter(String message, String reason, String originalTopic) {
        // Resolved inside the guard, not before it. The method's stated contract is that a
        // dead-letter failure never masks the original failure, and a resolution outside the
        // try breaks exactly that: an unmapped topic threw, and the exception escaped a
        // method whose entire purpose is to swallow DLQ problems.
        String deadLetterTopic = null;
        try {
            deadLetterTopic = topics.deadLetterFor(originalTopic);
            log.warn("Routing message from {} to {} because: {}", originalTopic, deadLetterTopic, reason);

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
