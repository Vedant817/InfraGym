package com.archforge.simulation.model;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.*;

@Value
@Builder
public class SimulationResult {
    String simulationId;
    String status;
    Instant startedAt;
    Instant completedAt;
    long durationMs;
    Map<String, Double> metrics;
    List<String> failures;
}
