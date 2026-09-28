package com.archforge.simulation.event;

import com.archforge.simulation.engine.SimulationRunner;
import com.archforge.simulation.model.SimulationResult;
import com.archforge.simulation.model.SimulationTask;
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
public class SimulationTaskConsumer {

    private final ObjectMapper objectMapper;
    private final SimulationRunner simulationRunner;

    @KafkaListener(
            topics = "${simulation.kafka.topics.simulation-tasks:simulation.tasks}",
            groupId = "${spring.kafka.consumer.group-id:simulation-engine-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeSimulationTask(@Payload String message, Acknowledgment acknowledgment) {
        log.debug("Received simulation task message");

        SimulationTask task;
        try {
            task = objectMapper.readValue(message, SimulationTask.class);
        } catch (Exception e) {
            log.error("Failed to parse simulation task message", e);
            acknowledgment.acknowledge();
            return;
        }

        if (task == null || task.getSimulationId() == null) {
            log.error("Invalid simulation task: null or missing simulationId");
            acknowledgment.acknowledge();
            return;
        }

        if (task.getCompiledGraph() == null) {
            log.error("Invalid simulation task: null compiledGraph");
            acknowledgment.acknowledge();
            return;
        }

        log.info("Processing simulation task: {}", task.getSimulationId());

        try {
            SimulationResult result = simulationRunner.run(task);
            acknowledgment.acknowledge();
            log.info("Successfully completed simulation: {}", result.getSimulationId());
        } catch (Exception e) {
            log.error("Failed to run simulation: {}", task.getSimulationId(), e);
        }
    }
}
