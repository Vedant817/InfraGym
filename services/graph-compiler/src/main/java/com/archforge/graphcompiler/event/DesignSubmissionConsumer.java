package com.archforge.graphcompiler.event;

import com.archforge.graphcompiler.schema.DesignSubmissionEvent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class DesignSubmissionConsumer {

    private final ObjectMapper objectMapper;
    private final CompilationService compilationService;
    private final SimulationTaskProducer taskProducer;

    @Value("${graph-compiler.kafka.topics.design-submissions:design.submissions}")
    private String designSubmissionsTopic;

    private final Set<String> processedSubmissions = ConcurrentHashMap.newKeySet();

    @KafkaListener(
            topics = "${graph-compiler.kafka.topics.design-submissions:design.submissions}",
            groupId = "${spring.kafka.consumer.group-id:graph-compiler-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeDesignSubmission(@Payload String message, Acknowledgment acknowledgment) {
        log.debug("Received design submission message");

        DesignSubmissionEvent event;
        try {
            event = objectMapper.readValue(message, DesignSubmissionEvent.class);
        } catch (Exception e) {
            log.error("Failed to parse design submission message", e);
            taskProducer.publishToDeadLetter(message, "PARSE_ERROR: " + e.getMessage(), designSubmissionsTopic);
            acknowledgment.acknowledge();
            return;
        }

        if (event == null || event.getSubmissionId() == null) {
            log.error("Invalid design submission event: null or missing submissionId");
            taskProducer.publishToDeadLetter(message, "INVALID_EVENT: null or missing submissionId", designSubmissionsTopic);
            acknowledgment.acknowledge();
            return;
        }

        if (!processedSubmissions.add(event.getSubmissionId())) {
            log.info("Duplicate submission detected: {}, skipping", event.getSubmissionId());
            acknowledgment.acknowledge();
            return;
        }

        if (event.getTopology() == null) {
            log.error("Invalid design submission event: null topology");
            taskProducer.publishToDeadLetter(message, "INVALID_EVENT: null topology", designSubmissionsTopic);
            acknowledgment.acknowledge();
            return;
        }

        log.info("Processing design submission: {}", event.getSubmissionId());

        try {
            compilationService.compileAndPublish(event);
            acknowledgment.acknowledge();
            log.info("Successfully processed design submission: {}", event.getSubmissionId());
        } catch (Exception e) {
            log.error("Failed to process design submission: {}", event.getSubmissionId(), e);
            taskProducer.publishToDeadLetter(message, "COMPILATION_ERROR: " + e.getMessage(), designSubmissionsTopic);
            acknowledgment.acknowledge();
        }
    }
}
