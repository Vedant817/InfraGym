package com.archforge.graphcompiler.enrichment;

import lombok.Builder;
import lombok.Value;

import java.util.*;

@Value
@Builder
public class SimulationParameters {
    Map<String, Double> capacity;
    Map<String, Double> latency;
    Map<String, Double> throughput;
    Map<String, Object> properties;
    List<String> failureModes;
    double recoveryTimeMs;

    public static SimulationParameters empty() {
        return SimulationParameters.builder()
                .capacity(Map.of())
                .latency(Map.of())
                .throughput(Map.of())
                .properties(Map.of())
                .failureModes(List.of())
                .recoveryTimeMs(0)
                .build();
    }

    public SimulationParameters mergeWith(Map<String, Object> userConfig) {
        if (userConfig == null || userConfig.isEmpty()) {
            return this;
        }

        Map<String, Double> mergedCapacity = new HashMap<>(capacity);
        Map<String, Double> mergedLatency = new HashMap<>(latency);
        Map<String, Double> mergedThroughput = new HashMap<>(throughput);
        Map<String, Object> mergedProperties = new HashMap<>(properties);

        for (Map.Entry<String, Object> entry : userConfig.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Number num) {
                double doubleValue = num.doubleValue();
                if (key.contains("capacity") || key.contains("rps") || key.contains("iops")) {
                    mergedCapacity.put(key, doubleValue);
                } else if (key.contains("latency") || key.contains("ms")) {
                    mergedLatency.put(key, doubleValue);
                } else if (key.contains("throughput") || key.contains("tps")) {
                    mergedThroughput.put(key, doubleValue);
                } else {
                    mergedProperties.put(key, value);
                }
            } else {
                mergedProperties.put(key, value);
            }
        }

        return SimulationParameters.builder()
                .capacity(mergedCapacity)
                .latency(mergedLatency)
                .throughput(mergedThroughput)
                .properties(mergedProperties)
                .failureModes(failureModes)
                .recoveryTimeMs(recoveryTimeMs)
                .build();
    }
}
