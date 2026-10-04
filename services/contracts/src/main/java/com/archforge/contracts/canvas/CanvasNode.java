package com.archforge.contracts.canvas;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

/**
 * A node exactly as the React Flow canvas emits it.
 *
 * <p>Field names match the React Flow payload one-for-one because this class is
 * the deserialisation target for documents stored in MongoDB and for
 * {@code design.submissions} messages. React Flow adds fields freely
 * ({@code selected}, {@code measured}, {@code dragging}, {@code zIndex}…), so
 * unknown properties are ignored by design — losing an unknown field is correct,
 * failing the request is not.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CanvasNode implements Serializable {

    private String id;
    /** Wire value of a {@link NodeType}; validated during compilation, not here. */
    private String type;
    private Position position;
    private NodeData data;
    /** Set when the user dropped the node inside a React Flow group node. */
    private String parentId;
    private Double width;
    private Double height;

    public CanvasNode() {
    }

    public CanvasNode(String id, String type, Position position, NodeData data) {
        this.id = id;
        this.type = type;
        this.position = position;
        this.data = data;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Position getPosition() {
        return position;
    }

    public void setPosition(Position position) {
        this.position = position;
    }

    public NodeData getData() {
        return data;
    }

    public void setData(NodeData data) {
        this.data = data;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public Double getWidth() {
        return width;
    }

    public void setWidth(Double width) {
        this.width = width;
    }

    public Double getHeight() {
        return height;
    }

    public void setHeight(Double height) {
        this.height = height;
    }

    public String label() {
        return data == null ? id : data.displayLabel(id);
    }

    @Override
    public String toString() {
        return "CanvasNode{id=" + id + ", type=" + type + ", label=" + label() + "}";
    }
}
