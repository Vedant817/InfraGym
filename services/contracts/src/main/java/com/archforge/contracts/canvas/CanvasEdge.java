package com.archforge.contracts.canvas;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

/**
 * A directed connection between two {@link CanvasNode}s.
 *
 * <p>Compiled into a {@code CompiledEdge}. An edge with no {@code targetHandle}
 * is a plain data-flow hop; handles let the frontend distinguish, for example, a
 * read from a write.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CanvasEdge implements Serializable {

    private String id;
    private String source;
    private String target;
    private String sourceHandle;
    private String targetHandle;
    private String label;
    /** React Flow edge renderer id (e.g. {@code smoothstep}); presentational only. */
    private String type;

    public CanvasEdge() {
    }

    public CanvasEdge(String id, String source, String target) {
        this.id = id;
        this.source = source;
        this.target = target;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getSourceHandle() {
        return sourceHandle;
    }

    public void setSourceHandle(String sourceHandle) {
        this.sourceHandle = sourceHandle;
    }

    public String getTargetHandle() {
        return targetHandle;
    }

    public void setTargetHandle(String targetHandle) {
        this.targetHandle = targetHandle;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @Override
    public String toString() {
        return "CanvasEdge{" + (id == null ? source + "->" + target : id) + "}";
    }
}
