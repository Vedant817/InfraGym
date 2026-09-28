package com.archforge.simulation.engine;

import com.archforge.simulation.model.SimulationTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class WorkloadGenerator {

    private final Random random = new Random();

    public WorkloadProfile generateWorkload(SimulationTask task, String scenarioType) {
        int baseRps = extractBaseRps(task);
        int durationSeconds = 60;

        return switch (scenarioType) {
            case "TRAFFIC_SPIKE" -> generateTrafficSpike(baseRps, durationSeconds);
            case "TRAFFIC_DROP" -> generateTrafficDrop(baseRps, durationSeconds);
            case "FLASH_SALE" -> generateFlashSale(baseRps, durationSeconds);
            case "DATABASE_OVERLOAD" -> generateDatabaseOverload(baseRps, durationSeconds);
            case "CACHE_FAILURE" -> generateCacheFailure(baseRps, durationSeconds);
            case "KAFKA_CONSUMER_FAILURE" -> generateKafkaConsumerFailure(baseRps, durationSeconds);
            case "NETWORK_DEGRADATION" -> generateNetworkDegradation(baseRps, durationSeconds);
            case "SERVICE_FAILURE" -> generateServiceFailure(baseRps, durationSeconds);
            default -> generateNormalWorkload(baseRps, durationSeconds);
        };
    }

    private WorkloadProfile generateNormalWorkload(int baseRps, int durationSeconds) {
        List<WorkloadPhase> phases = new ArrayList<>();
        phases.add(new WorkloadPhase("warmup", 5, (int) (baseRps * 0.5)));
        phases.add(new WorkloadPhase("steady", durationSeconds - 10, baseRps));
        phases.add(new WorkloadPhase("cooldown", 5, (int) (baseRps * 0.3)));
        return new WorkloadProfile(phases, durationSeconds);
    }

    private WorkloadProfile generateTrafficSpike(int baseRps, int durationSeconds) {
        List<WorkloadPhase> phases = new ArrayList<>();
        phases.add(new WorkloadPhase("normal", 10, baseRps));
        phases.add(new WorkloadPhase("spike", 10, baseRps * 5));
        phases.add(new WorkloadPhase("recovery", 10, (int) (baseRps * 1.5)));
        phases.add(new WorkloadPhase("steady", durationSeconds - 30, baseRps));
        return new WorkloadProfile(phases, durationSeconds);
    }

    private WorkloadProfile generateTrafficDrop(int baseRps, int durationSeconds) {
        List<WorkloadPhase> phases = new ArrayList<>();
        phases.add(new WorkloadPhase("normal", 10, baseRps));
        phases.add(new WorkloadPhase("drop", 10, (int) (baseRps * 0.1)));
        phases.add(new WorkloadPhase("recovery", durationSeconds - 20, baseRps));
        return new WorkloadProfile(phases, durationSeconds);
    }

    private WorkloadProfile generateFlashSale(int baseRps, int durationSeconds) {
        List<WorkloadPhase> phases = new ArrayList<>();
        phases.add(new WorkloadPhase("pre-sale", 5, (int) (baseRps * 0.8)));
        phases.add(new WorkloadPhase("flash", 15, baseRps * 10));
        phases.add(new WorkloadPhase("post-flash", durationSeconds - 20, (int) (baseRps * 1.2)));
        return new WorkloadProfile(phases, durationSeconds);
    }

    private WorkloadProfile generateDatabaseOverload(int baseRps, int durationSeconds) {
        List<WorkloadPhase> phases = new ArrayList<>();
        phases.add(new WorkloadPhase("normal", 10, baseRps));
        phases.add(new WorkloadPhase("overload", 20, baseRps * 3));
        phases.add(new WorkloadPhase("recovery", durationSeconds - 30, baseRps));
        return new WorkloadProfile(phases, durationSeconds);
    }

    private WorkloadProfile generateCacheFailure(int baseRps, int durationSeconds) {
        List<WorkloadPhase> phases = new ArrayList<>();
        phases.add(new WorkloadPhase("normal", 10, baseRps));
        phases.add(new WorkloadPhase("cache-down", 20, baseRps));
        phases.add(new WorkloadPhase("recovery", durationSeconds - 30, baseRps));
        return new WorkloadProfile(phases, durationSeconds);
    }

    private WorkloadProfile generateKafkaConsumerFailure(int baseRps, int durationSeconds) {
        List<WorkloadPhase> phases = new ArrayList<>();
        phases.add(new WorkloadPhase("normal", 10, baseRps));
        phases.add(new WorkloadPhase("consumer-lag", 20, baseRps));
        phases.add(new WorkloadPhase("recovery", durationSeconds - 30, baseRps));
        return new WorkloadProfile(phases, durationSeconds);
    }

    private WorkloadProfile generateNetworkDegradation(int baseRps, int durationSeconds) {
        List<WorkloadPhase> phases = new ArrayList<>();
        phases.add(new WorkloadPhase("normal", 10, baseRps));
        phases.add(new WorkloadPhase("degraded", 20, (int) (baseRps * 0.7)));
        phases.add(new WorkloadPhase("recovery", durationSeconds - 30, baseRps));
        return new WorkloadProfile(phases, durationSeconds);
    }

    private WorkloadProfile generateServiceFailure(int baseRps, int durationSeconds) {
        List<WorkloadPhase> phases = new ArrayList<>();
        phases.add(new WorkloadPhase("normal", 10, baseRps));
        phases.add(new WorkloadPhase("service-down", 15, 0));
        phases.add(new WorkloadPhase("recovery", durationSeconds - 25, baseRps));
        return new WorkloadProfile(phases, durationSeconds);
    }

    private int extractBaseRps(SimulationTask task) {
        if (task.getCompiledGraph() == null || task.getCompiledGraph().getNodes().isEmpty()) {
            return 1000;
        }
        return task.getCompiledGraph().getNodes().stream()
                .filter(n -> n.getType() != null && n.getType().name().equals("SERVICE"))
                .findFirst()
                .map(n -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Double> capacity = n.getCapacity();
                    if (capacity != null && capacity.containsKey("requestsPerSecond")) {
                        return capacity.get("requestsPerSecond").intValue();
                    }
                    return 1000;
                })
                .orElse(1000);
    }

    public record WorkloadPhase(String name, int durationSeconds, int targetRps) {}

    public record WorkloadProfile(List<WorkloadPhase> phases, int totalDurationSeconds) {
        public int getRpsAtTime(int elapsedSeconds) {
            int accumulated = 0;
            for (WorkloadPhase phase : phases) {
                if (elapsedSeconds < accumulated + phase.durationSeconds()) {
                    return phase.targetRps();
                }
                accumulated += phase.durationSeconds();
            }
            return phases.get(phases.size() - 1).targetRps();
        }
    }
}
