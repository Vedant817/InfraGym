package com.archforge.telemetry.model;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;

@Value
@Builder
public class AggregatedMetrics {
    String simulationId;
    String nodeId;
    String metricName;
    double min;
    double max;
    double avg;
    double count;
    Instant windowStart;
    Instant windowEnd;
    String unit;
}
