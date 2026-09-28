package com.archforge.graphcompiler.event;

import com.archforge.graphcompiler.schema.DesignSubmissionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DesignSubmissionConsumer {

    private final ObjectMapper objectMapper;
    private final CompilationService compilationService;
    private final SimulationTaskProducer taskProducer;

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
            taskProducer.publishToDeadLetter(message, "PARSE_ERROR: " + e.getMessage(), "design.submissions");
            acknowledgment.acknowledge();
            return;
        }

        if (event == null || event.getSubmissionId() == null) {
            log.error("Invalid design submission event: null or missing submissionId");
            taskProducer.publishToDeadLetter(message, "INVALID_EVENT: null or missing submissionId", "design.submissions");
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
            taskProducer.publishToDeadLetter(message, "COMPILATION_ERROR: " + e.getMessage(), "design.submissions");
            acknowledgment.acknowledge();
        }
    }
}
