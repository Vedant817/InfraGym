package com.archforge.graphcompiler.model;

import lombok.Builder;
import lombok.Value;

import java.util.*;

@Value
@Builder
public class CompiledGraph {
    List<CompiledNode> nodes;
    List<CompiledEdge> edges;
    Map<String, Object> graphMetadata;
    String name;
    String description;
    List<String> validationErrors;

    public static class CompiledGraphBuilder {
        public CompiledGraph build() {
            nodes = nodes == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(nodes));
            edges = edges == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(edges));
            graphMetadata = graphMetadata == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(graphMetadata));
            validationErrors = validationErrors == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(validationErrors));

            return new CompiledGraph(nodes, edges, graphMetadata, name, description, validationErrors);
        }
    }
}
