package com.archforge.ai.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AggregatedMetricsEvent {
    String simulationId;
    String nodeId;
    String metricName;
    double min;
    double max;
    double avg;
    double count;
    String windowStart;
    String windowEnd;
    String unit;
}
