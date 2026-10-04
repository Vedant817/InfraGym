package com.archforge.contracts.canvas;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Free-form payload attached to a canvas node.
 *
 * <p>Two buckets, deliberately kept apart:
 * <ul>
 *   <li>{@code config} — user-supplied overrides of simulation parameters
 *       (capacity, latency, throughput). This is the only bucket the compiler
 *       reads, and its keys must be declared in
 *       {@code SimulationParameterRegistry.mergeWith}.</li>
 *   <li>{@code additionalProperties} — everything else the canvas UI attaches
 *       (icon colour, notes, collapsed flag). Round-tripped untouched so the
 *       frontend never loses data, and never interpreted by the backend.</li>
 * </ul>
 *
 * <p>Separating them is what stops UI churn from silently changing simulation
 * semantics.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NodeData {

    private String label;
    private String description;
    private Map<String, Object> config = new LinkedHashMap<>();

    @JsonIgnore
    private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

    public NodeData() {
    }

    public NodeData(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config == null ? new LinkedHashMap<>() : new LinkedHashMap<>(config);
    }

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    @JsonAnySetter
    public void setAdditionalProperty(String key, Object value) {
        additionalProperties.put(key, value);
    }

    /** Label if set, otherwise the node id is the only thing a human can read. */
    public String displayLabel(String nodeId) {
        return (label == null || label.isBlank()) ? nodeId : label;
    }
}
