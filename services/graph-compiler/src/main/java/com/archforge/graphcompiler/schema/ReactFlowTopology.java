package com.archforge.graphcompiler.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReactFlowTopology {
    private List<ReactFlowNode> nodes;
    private List<ReactFlowEdge> edges;
}
