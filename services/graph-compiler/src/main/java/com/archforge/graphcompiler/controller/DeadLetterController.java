package com.archforge.graphcompiler.controller;

import com.archforge.messaging.KafkaTopics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Operator tooling for inspecting and replaying dead-lettered submissions.
 *
 * <p>Three defects in the previous version are worth naming, because each reported
 * success for work that had not happened:
 *
 * <ol>
 *   <li><b>Two incompatible dead-letter envelopes.</b> Records produced by the
 *       container's {@code DeadLetterPublishingRecoverer} carry
 *       {@code topic/partition/offset/key/value/headers}. Records produced by the
 *       hand-rolled writer carry {@code message/reason}. This endpoint only
 *       understood the second shape, so replaying a container-produced record threw
 *       a {@code NullPointerException} on {@code dlqEvent.get("message")} and
 *       surfaced as a 500. Both shapes are handled now.</li>
 *   <li><b>"REPLAYED" was a lie.</b> The response was returned immediately after an
 *       asynchronous {@code send}, so a broker outage still produced
 *       {@code 200 REPLAYED}. The send is now awaited with a bounded timeout.</li>
 *   <li><b>{@code new Date().toString()}</b> is a locale- and timezone-dependent
 *       format no standard parser accepts. Replaced with ISO-8601.</li>
 * </ol>
 *
 * <p>Replay is inherently at-least-once and the record is not removed from the
 * dead-letter topic, so replaying twice processes twice. That is stated in the
 * response rather than left for an operator to discover.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class DeadLetterController {

    private static final long REPLAY_TIMEOUT_SECONDS = 30;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaTopics topics;

    @PostMapping("/replay-dlq")
    public ResponseEntity<Map<String, Object>> replayDeadLetter(@RequestBody String dlqMessage) {
        if (dlqMessage == null || dlqMessage.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Request body is required and must contain a dead-letter record"));
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(dlqMessage);
        } catch (Exception e) {
            log.warn("Rejecting DLQ replay: body is not valid JSON: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Body is not valid JSON"));
        }

        if (node == null || !node.isObject()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Body must be a JSON object"));
        }

        // Container-produced envelope uses "value"; hand-rolled envelope uses "message".
        JsonNode payloadNode = node.hasNonNull("message") ? node.get("message") : node.get("value");
        if (payloadNode == null || payloadNode.isNull()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Dead-letter record contains neither 'message' nor 'value'; nothing to replay",
                    "detectedKeys", String.join(",", fieldNames(node))));
        }

        String payload = payloadNode.asText();
        String reason = textOrDefault(node, "reason", "no reason recorded");
        String sourceTopic = textOrDefault(node, "originalTopic", topics.designSubmissions());
        String targetTopic = topics.designSubmissions();

        log.warn("Replaying dead-lettered submission from {} to {} (reason: {})",
                sourceTopic, targetTopic, reason);

        try {
            var result = kafkaTemplate.send(targetTopic, null, payload)
                    .get(REPLAY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Replayed dead-lettered submission to {}-{}@{}", targetTopic,
                    result.getRecordMetadata().partition(), result.getRecordMetadata().offset());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "REPLAYED");
            body.put("originalTopic", sourceTopic);
            body.put("targetTopic", targetTopic);
            body.put("originalReason", reason);
            body.put("replayedAt", Instant.now().toString());
            body.put("note", "The record remains in the dead-letter topic. Replay is at-least-once.");
            return ResponseEntity.ok(body);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Interrupted while replaying; outcome unknown"));
        } catch (Exception e) {
            log.error("Failed to replay dead-lettered submission to {}", targetTopic, e);
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Replay failed: the broker did not acknowledge the message",
                    "detail", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    @GetMapping("/dlq-info")
    public ResponseEntity<Map<String, Object>> getDLQInfo() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dlqTopic", topics.deadLetterFor(topics.designSubmissions()));
        body.put("originalTopic", topics.designSubmissions());
        body.put("envelopeKeys", List.of("message", "reason", "originalTopic", "timestamp"));
        body.put("description", "Dead letter topic for submissions the compiler could not process");
        return ResponseEntity.ok(body);
    }

    private String textOrDefault(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    private List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
