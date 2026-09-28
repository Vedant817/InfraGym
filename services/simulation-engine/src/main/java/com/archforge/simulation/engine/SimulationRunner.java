package com.archforge.simulation.engine;

import com.archforge.simulation.model.SimulationResult;
import com.archforge.simulation.model.SimulationTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class SimulationRunner {

    private final WorkloadGenerator workloadGenerator;
    private final FailureInjector failureInjector;
    private final TelemetryProducer telemetryProducer;

    public SimulationResult run(SimulationTask task) {
        log.info("Starting simulation: {}", task.getSimulationId());

        Instant startTime = Instant.now();
        String scenarioType = task.getScenarioType() != null ? task.getScenarioType() : "NORMAL";

        WorkloadGenerator.WorkloadProfile workload = workloadGenerator.generateWorkload(task, scenarioType);
        List<FailureInjector.FailureScenario> failures = failureInjector.generateFailures(task, scenarioType);

        log.info("Simulation {} running with scenario: {}, duration: {}s, failures: {}",
                task.getSimulationId(), scenarioType, workload.totalDurationSeconds(), failures.size());

        for (int second = 0; second < workload.totalDurationSeconds(); second++) {
            telemetryProducer.recordMetrics(task, second, workload, failures);

            if (second % 10 == 0) {
                int rps = workload.getRpsAtTime(second);
                log.debug("Simulation {} at {}s: {} rps", task.getSimulationId(), second, rps);
            }
        }

        Instant endTime = Instant.now();
        long durationMs = java.time.Duration.between(startTime, endTime).toMillis();

        SimulationResult result = SimulationResult.builder()
                .simulationId(task.getSimulationId())
                .status("COMPLETED")
                .startedAt(startTime)
                .completedAt(endTime)
                .durationMs(durationMs)
                .metrics(Collections.emptyMap())
                .failures(failures.stream().map(FailureInjector.FailureScenario::description).toList())
                .build();

        log.info("Simulation {} completed in {} ms", task.getSimulationId(), durationMs);
        return result;
    }
}
