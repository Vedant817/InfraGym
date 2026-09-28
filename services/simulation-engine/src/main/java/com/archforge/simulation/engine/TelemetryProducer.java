package com.archforge.simulation.engine;

import com.archforge.simulation.model.SimulationTask;
import com.archforge.simulation.model.TelemetryEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${simulation.kafka.topics.simulation-telemetry:simulation.telemetry}")
    private String telemetryTopic;

    private final Map<String, NodeMetrics> nodeMetrics = new ConcurrentHashMap<>();

    public void recordMetrics(SimulationTask task, int elapsedSeconds, WorkloadGenerator.WorkloadProfile workload,
                               List<FailureInjector.FailureScenario> failures) {
        int currentRps = workload.getRpsAtTime(elapsedSeconds);

        for (var node : task.getCompiledGraph().getNodes()) {
            String nodeId = node.getId();
            NodeMetrics metrics = nodeMetrics.computeIfAbsent(nodeId, k -> new NodeMetrics());

            double latency = calculateLatency(node, elapsedSeconds, failures);
            double errorRate = calculateErrorRate(node, elapsedSeconds, failures);
            double throughput = calculateThroughput(node, currentRps, errorRate);

            metrics.addLatency(latency);
            metrics.addError(errorRate);
            metrics.addThroughput(throughput);

            publishMetric(task.getSimulationId(), nodeId, "latency_ms", latency, "ms");
            publishMetric(task.getSimulationId(), nodeId, "error_rate", errorRate, "percent");
            publishMetric(task.getSimulationId(), nodeId, "throughput_rps", throughput, "rps");

            if (node.getType() != null) {
                switch (node.getType().name()) {
                    case "QUEUE" -> {
                        double lag = calculateQueueLag(node, elapsedSeconds, failures);
                        publishMetric(task.getSimulationId(), nodeId, "queue_depth", lag, "count");
                    }
                    case "CACHE" -> {
                        double hitRate = calculateCacheHitRate(node, elapsedSeconds, failures);
                        publishMetric(task.getSimulationId(), nodeId, "cache_hit_rate", hitRate, "percent");
                    }
                    case "DATABASE" -> {
                        double utilization = calculateDbUtilization(node, elapsedSeconds, failures);
                        publishMetric(task.getSimulationId(), nodeId, "db_utilization", utilization, "percent");
                    }
                    default -> {
                    }
                }
            }
        }
    }

    public void publishMetric(String simulationId, String nodeId, String metricName, double value, String unit) {
        try {
            TelemetryEvent event = TelemetryEvent.builder()
                    .simulationId(simulationId)
                    .nodeId(nodeId)
                    .metricName(metricName)
                    .value(value)
                    .timestamp(Instant.now())
                    .unit(unit)
                    .build();

            String key = simulationId + ":" + nodeId;
            String json = objectMapper.writeValueAsString(event);

            kafkaTemplate.send(telemetryTopic, key, json);
        } catch (Exception e) {
            log.error("Failed to publish metric: {} for node: {}", metricName, nodeId, e);
        }
    }

    private double calculateLatency(com.archforge.graphcompiler.model.CompiledNode node, int elapsedSeconds,
                                     List<FailureInjector.FailureScenario> failures) {
        @SuppressWarnings("unchecked")
        Map<String, Double> latencyMap = (Map<String, Double>) node.getSimulationMetadata().get("latency");
        double baseLatency = latencyMap != null ? latencyMap.getOrDefault("p50Ms", 20.0) : 20.0;

        for (FailureInjector.FailureScenario failure : failures) {
            if (failure.nodeId().equals(node.getId()) && new FailureInjector().isFailureActive(failure, elapsedSeconds)) {
                return baseLatency * 5;
            }
        }

        return baseLatency * (1 + Math.random() * 0.2);
    }

    private double calculateErrorRate(com.archforge.graphcompiler.model.CompiledNode node, int elapsedSeconds,
                                       List<FailureInjector.FailureScenario> failures) {
        for (FailureInjector.FailureScenario failure : failures) {
            if (failure.nodeId().equals(node.getId()) && new FailureInjector().isFailureActive(failure, elapsedSeconds)) {
                return 0.5 + Math.random() * 0.3;
            }
        }
        return Math.random() * 0.01;
    }

    private double calculateThroughput(com.archforge.graphcompiler.model.CompiledNode node, int currentRps, double errorRate) {
        @SuppressWarnings("unchecked")
        Map<String, Double> capacity = node.getCapacity();
        double maxThroughput = capacity != null ? capacity.getOrDefault("requestsPerSecond", 1000.0) : 1000.0;
        return Math.min(currentRps, maxThroughput) * (1 - errorRate);
    }

    private double calculateQueueLag(com.archforge.graphcompiler.model.CompiledNode node, int elapsedSeconds,
                                      List<FailureInjector.FailureScenario> failures) {
        for (FailureInjector.FailureScenario failure : failures) {
            if (failure.nodeId().equals(node.getId()) && new FailureInjector().isFailureActive(failure, elapsedSeconds)) {
                return 10000 + Math.random() * 50000;
            }
        }
        return Math.random() * 100;
    }

    private double calculateCacheHitRate(com.archforge.graphcompiler.model.CompiledNode node, int elapsedSeconds,
                                          List<FailureInjector.FailureScenario> failures) {
        for (FailureInjector.FailureScenario failure : failures) {
            if (failure.nodeId().equals(node.getId()) && new FailureInjector().isFailureActive(failure, elapsedSeconds)) {
                return 0.1;
            }
        }
        return 0.7 + Math.random() * 0.25;
    }

    private double calculateDbUtilization(com.archforge.graphcompiler.model.CompiledNode node, int elapsedSeconds,
                                           List<FailureInjector.FailureScenario> failures) {
        for (FailureInjector.FailureScenario failure : failures) {
            if (failure.nodeId().equals(node.getId()) && new FailureInjector().isFailureActive(failure, elapsedSeconds)) {
                return 0.95 + Math.random() * 0.05;
            }
        }
        return 0.3 + Math.random() * 0.4;
    }

    private static class NodeMetrics {
        private final List<Double> latencies = new ArrayList<>();
        private final List<Double> errors = new ArrayList<>();
        private final List<Double> throughputs = new ArrayList<>();

        void addLatency(double latency) {
            latencies.add(latency);
        }

        void addError(double error) {
            errors.add(error);
        }

        void addThroughput(double throughput) {
            throughputs.add(throughput);
        }
    }
}
