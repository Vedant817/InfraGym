package com.archforge.ai.event;

import com.archforge.ai.model.AggregatedMetricsEvent;
import com.archforge.ai.service.ArchitectureEvaluationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class AggregatedMetricsConsumer {

    private final ObjectMapper objectMapper;
    private final ArchitectureEvaluationService evaluationService;

    private final Map<String, List<AggregatedMetricsEvent>> metricsBuffer = new ConcurrentHashMap<>();

    @KafkaListener(
            topics = "${ai.kafka.topics.aggregated-metrics:aggregated.metrics}",
            groupId = "${spring.kafka.consumer.group-id:ai-evaluator-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeAggregatedMetrics(@Payload String message, Acknowledgment acknowledgment) {
        log.debug("Received aggregated metrics message");

        try {
            AggregatedMetricsEvent event = objectMapper.readValue(message, AggregatedMetricsEvent.class);

            if (event == null || event.getSimulationId() == null) {
                acknowledgment.acknowledge();
                return;
            }

            metricsBuffer.computeIfAbsent(event.getSimulationId(), k -> new ArrayList<>()).add(event);

            if (metricsBuffer.get(event.getSimulationId()).size() >= 5) {
                List<AggregatedMetricsEvent> metrics = new ArrayList<>(metricsBuffer.get(event.getSimulationId()));
                metricsBuffer.remove(event.getSimulationId());

                log.info("Triggering evaluation for simulation: {}", event.getSimulationId());
                evaluationService.evaluate(event.getSimulationId(), event.getSimulationId(), metrics);
            }

            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process aggregated metrics", e);
            acknowledgment.acknowledge();
        }
    }
}
