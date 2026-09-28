package com.archforge.graphcompiler.model;

import com.archforge.graphcompiler.schema.NodeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompiledNode {
    private String id;
    private NodeType type;
    private String label;
    private Set<String> dependencies;
    private Set<String> dependents;
    private Map<String, Object> simulationMetadata;
    private Map<String, Integer> inputPorts;
    private Map<String, Integer> outputPorts;
    private Map<String, Double> capacity;
    private String failureMode;
    private int recoveryTimeMs;
    private int topologicalOrder;
}
