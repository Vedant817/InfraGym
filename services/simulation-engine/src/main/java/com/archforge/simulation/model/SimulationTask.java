package com.archforge.simulation.model;

import com.archforge.graphcompiler.model.CompiledGraph;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class SimulationTask {
    String simulationId;
    String submissionId;
    String userId;
    String sessionId;
    CompiledGraph compiledGraph;
    Instant createdAt;
    String scenarioType;
}
