package com.archforge.graphcompiler.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompiledGraph {
    private List<CompiledNode> nodes;
    private List<CompiledEdge> edges;
    private Map<String, Object> graphMetadata;
    private String name;
    private String description;
    private List<String> validationErrors;
}
