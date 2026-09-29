package com.archforge.contracts.canvas;

import java.time.Instant;
import java.util.Map;

public record SimulationTelemetry(
    String submissionId,
    String nodeId,
    double latencyP99Ms,
    long queueDepth,
    long droppedPackets,
    Instant timestamp,
    Map<String, Object> metadata
) {}