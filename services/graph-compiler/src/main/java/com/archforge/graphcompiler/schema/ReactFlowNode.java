package com.archforge.graphcompiler.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReactFlowNode {
    private String id;
    private String type;
    private Position position;
    private NodeData data;
}
