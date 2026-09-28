package com.archforge.simulation.model;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class TelemetryEvent {
    String simulationId;
    String nodeId;
    String metricName;
    double value;
    Instant timestamp;
    String unit;
}
