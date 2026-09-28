package com.archforge.graphcompiler.controller;

import com.archforge.graphcompiler.event.SimulationTaskProducer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class DeadLetterController {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${graph-compiler.kafka.topics.design-submissions:design.submissions}")
    private String designSubmissionsTopic;

    @Value("${graph-compiler.kafka.topics.dead-letter:graph-compiler.dlt}")
    private String deadLetterTopic;

    @PostMapping("/replay-dlq")
    public ResponseEntity<Map<String, Object>> replayDeadLetter(@RequestBody String dlqMessage) {
        log.info("Received DLQ replay request");

        try {
            JsonNode dlqEvent = objectMapper.readTree(dlqMessage);
            String originalMessage = dlqEvent.get("message").asText();
            String reason = dlqEvent.get("reason").asText();

            log.info("Replaying DLQ message with reason: {}", reason);

            kafkaTemplate.send(designSubmissionsTopic, originalMessage)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to replay DLQ message", ex);
                        } else {
                            log.info("Successfully replayed DLQ message");
                        }
                    });

            return ResponseEntity.ok(Map.of(
                    "status", "REPLAYED",
                    "originalReason", reason,
                    "timestamp", new Date().toString()
            ));
        } catch (Exception e) {
            log.error("Failed to parse DLQ message", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid DLQ message format",
                    "details", e.getMessage()
            ));
        }
    }

    @GetMapping("/dlq-info")
    public ResponseEntity<Map<String, Object>> getDLQInfo() {
        return ResponseEntity.ok(Map.of(
                "dlqTopic", deadLetterTopic,
                "originalTopic", designSubmissionsTopic,
                "description", "Dead letter queue for failed design submissions"
        ));
    }
}
