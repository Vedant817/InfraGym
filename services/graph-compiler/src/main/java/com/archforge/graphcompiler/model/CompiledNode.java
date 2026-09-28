package com.archforge.graphcompiler.model;

import com.archforge.graphcompiler.schema.NodeType;
import lombok.Builder;
import lombok.Value;

import java.util.*;

@Value
@Builder
public class CompiledNode {
    String id;
    NodeType type;
    String label;
    Set<String> dependencies;
    Set<String> dependents;
    Map<String, Object> simulationMetadata;
    Map<String, Integer> inputPorts;
    Map<String, Integer> outputPorts;
    Map<String, Double> capacity;
    String failureMode;
    int recoveryTimeMs;
    int topologicalOrder;

    public static class CompiledNodeBuilder {
        public CompiledNode build() {
            Objects.requireNonNull(id, "id must not be null");
            if (topologicalOrder < 0) throw new IllegalArgumentException("topologicalOrder must not be negative");

            dependencies = dependencies == null ? Set.of() : Collections.unmodifiableSet(new HashSet<>(dependencies));
            dependents = dependents == null ? Set.of() : Collections.unmodifiableSet(new HashSet<>(dependents));
            simulationMetadata = simulationMetadata == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(simulationMetadata));
            inputPorts = inputPorts == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(inputPorts));
            outputPorts = outputPorts == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(outputPorts));
            capacity = capacity == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(capacity));

            return new CompiledNode(id, type, label, dependencies, dependents, simulationMetadata, inputPorts, outputPorts, capacity, failureMode, recoveryTimeMs, topologicalOrder);
        }
    }
}
