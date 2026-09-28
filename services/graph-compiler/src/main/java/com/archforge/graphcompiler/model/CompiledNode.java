package com.archforge.graphcompiler.model;

import com.archforge.graphcompiler.schema.NodeType;
import lombok.Builder;
import lombok.Data;

import java.util.*;

@Data
@Builder
public class CompiledNode {
    private String id;
    private NodeType type;
    private String label;
    private Set<String> dependencies;
    private Set<String> dependents;
    private Map<String, Object> simulationMetadata;
    private int topologicalOrder;
}
