package com.archforge.simulation.event;

import com.archforge.contracts.event.SimulationLifecycleEvent;
import com.archforge.contracts.lifecycle.SimulationStatus;
import com.archforge.messaging.KafkaTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Publishes simulation lifecycle transitions and the engine's own summary.
 *
 * <p>Lifecycle events are the pipeline's "the run is over" signal, and unlike
 * submission progress they are load-bearing: the aggregator waits for the terminal
 * transition before building the summary the AI evaluator reads. So this sends
 * synchronously, bounded by a timeout, and counts a failure loudly — a lost
 * {@code COMPLETED} means evaluation never fires and the only symptom is a submission
 * that sits at {@code AGGREGATING} forever.
 *
 * <p>Fire-and-forget would be wrong here. It is right for
 * {@link com.archforge.messaging.SubmissionLifecyclePublisher}, where a dropped
 * progress signal is superseded by the next stage's.
 */
@Slf4j
@Component
public class SimulationLifecyclePublisher {

    /** How long a lifecycle publish may take before it is treated as failed. */
    static final int AWAIT_TIMEOUT_SECONDS = 15;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public SimulationLifecyclePublisher(KafkaTemplate<String, String> kafkaTemplate,
                                        ObjectMapper objectMapper,
                                        MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Publishes a lifecycle transition and waits for the broker.
     *
     * @return whether the broker acknowledged it
     */
    public boolean publish(String topic, SimulationLifecycleEvent event) {
        if (event == null) {
            return false;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            meterRegistry.counter("simulation.lifecycle.serialize_failed").increment();
            log.error("Failed to serialise {} lifecycle for simulation {}",
                    event.status(), event.simulationId(), e);
            return false;
        }

        SimulationStatus status = event.status();
        try {
            // Keyed by simulation so one run's transitions stay ordered on a partition.
            CompletableFuture<?> future = kafkaTemplate.send(topic, event.simulationId(), payload);
            future.get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            meterRegistry.counter("simulation.lifecycle.published",
                    "status", status.getValue()).increment();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            meterRegistry.counter("simulation.lifecycle.publish_failed",
                    "status", status.getValue()).increment();
            log.error("Interrupted publishing {} lifecycle for simulation {}",
                    status, event.simulationId());
            return false;
        } catch (Exception e) {
            meterRegistry.counter("simulation.lifecycle.publish_failed",
                    "status", status.getValue()).increment();
            log.error("Failed to publish {} lifecycle for simulation {} to {}: {}",
                    status, event.simulationId(), topic, e.getMessage());
            return false;
        }
    }
}
