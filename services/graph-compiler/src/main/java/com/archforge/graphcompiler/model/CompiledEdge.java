package com.archforge.graphcompiler.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CompiledEdge {
    private String id;
    private String source;
    private String target;
    private String label;
}
