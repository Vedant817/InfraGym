package com.archforge.graphcompiler.enrichment;

import lombok.Builder;
import lombok.Data;

import java.util.*;

@Data
@Builder
public class SimulationParameters {
    private Map<String, Double> capacity;
    private Map<String, Double> latency;
    private Map<String, Double> throughput;
    private Map<String, Object> properties;
    private List<String> failureModes;
    private double recoveryTimeMs;

    public static SimulationParameters empty() {
        return SimulationParameters.builder()
                .capacity(new HashMap<>())
                .latency(new HashMap<>())
                .throughput(new HashMap<>())
                .properties(new HashMap<>())
                .failureModes(new ArrayList<>())
                .recoveryTimeMs(0)
                .build();
    }
}
