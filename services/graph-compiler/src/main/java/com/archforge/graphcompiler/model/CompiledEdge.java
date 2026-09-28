package com.archforge.graphcompiler.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompiledEdge {
    private String id;
    private String source;
    private String target;
    private String label;
    private String protocol;
    private Double bandwidthMbps;
    private Double latencyMs;
    private String direction;
}
