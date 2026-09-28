package com.archforge.graphcompiler.model;

import lombok.Builder;
import lombok.Data;

import java.util.*;

@Data
@Builder
public class CompiledGraph {
    private List<CompiledNode> nodes;
    private List<CompiledEdge> edges;
    private Map<String, Object> graphMetadata;
}
