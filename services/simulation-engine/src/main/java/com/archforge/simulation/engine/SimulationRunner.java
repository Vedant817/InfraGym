package com.archforge.simulation.engine;

import com.archforge.simulation.model.SimulationResult;
import com.archforge.simulation.model.SimulationTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class SimulationRunner {

    private final WorkloadGenerator workloadGenerator;
    private final FailureInjector failureInjector;
    private final TelemetryProducer telemetryProducer;
    private final ExecutorService executorService;

    public SimulationRunner(WorkloadGenerator workloadGenerator, FailureInjector failureInjector,
                             TelemetryProducer telemetryProducer) {
        this.workloadGenerator = workloadGenerator;
        this.failureInjector = failureInjector;
        this.telemetryProducer = telemetryProducer;
        this.executorService = Executors.newFixedThreadPool(10, r -> {
            Thread t = new Thread(r, "simulation-worker-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    public SimulationResult run(SimulationTask task) {
        log.info("Starting simulation: {}", task.getSimulationId());

        Instant startTime = Instant.now();
        String scenarioType = task.getScenarioType() != null ? task.getScenarioType() : "NORMAL";

        WorkloadGenerator.WorkloadProfile workload = workloadGenerator.generateWorkload(task, scenarioType);
        List<FailureInjector.FailureScenario> failures = failureInjector.generateFailures(task, scenarioType);

        log.info("Simulation {} running with scenario: {}, duration: {}s, failures: {}",
                task.getSimulationId(), scenarioType, workload.totalDurationSeconds(), failures.size());

        CompletableFuture<Void> simulationFuture = CompletableFuture.runAsync(() -> {
            for (int second = 0; second < workload.totalDurationSeconds(); second++) {
                telemetryProducer.recordMetrics(task, second, workload, failures);

                if (second % 10 == 0) {
                    int rps = workload.getRpsAtTime(second);
                    log.debug("Simulation {} at {}s: {} rps", task.getSimulationId(), second, rps);
                }
            }
        }, executorService);

        try {
            simulationFuture.get(workload.totalDurationSeconds() + 30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.error("Simulation {} timed out", task.getSimulationId(), e);
            simulationFuture.cancel(true);
        } catch (Exception e) {
            log.error("Simulation {} failed", task.getSimulationId(), e);
        }

        Instant endTime = Instant.now();
        long durationMs = java.time.Duration.between(startTime, endTime).toMillis();

        Map<String, Double> aggregateMetrics = new HashMap<>();
        if (task.getCompiledGraph() != null && task.getCompiledGraph().getNodes() != null) {
            for (var node : task.getCompiledGraph().getNodes()) {
                aggregateMetrics.putAll(telemetryProducer.getAggregateMetrics(node.getId()));
                telemetryProducer.clearMetrics(node.getId());
            }
        }

        SimulationResult result = SimulationResult.builder()
                .simulationId(task.getSimulationId())
                .status("COMPLETED")
                .startedAt(startTime)
                .completedAt(endTime)
                .durationMs(durationMs)
                .metrics(aggregateMetrics)
                .failures(failures.stream().map(FailureInjector.FailureScenario::description).toList())
                .build();

        log.info("Simulation {} completed in {} ms", task.getSimulationId(), durationMs);
        return result;
    }

    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
