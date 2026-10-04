package com.archforge.contracts.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import java.io.Serializable;
import java.util.Objects;

/**
 * A directed, weighted link in the compiled DAG.
 *
 * <p>{@code latencyMs} is the propagation cost of the link itself and is added
 * to the service time of whichever node is traversing it; {@code bandwidthMbps}
 * caps the link and, when exceeded, adds queuing delay rather than dropping the
 * request. Both are derived from the edge label/config by the compiler, never
 * invented by the simulator.
 *
 * <h2>Jackson</h2>
 * {@link JsonDeserialize}{@code (builder = CompiledEdge.Builder.class)} is required for
 * the same reason as {@link CompiledNode}: a private builder-only constructor is not a
 * creator Jackson can use.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(builder = CompiledEdge.Builder.class)
public final class CompiledEdge implements Serializable {

    /** Default propagation delay when an edge carries no explicit figure. */
    public static final double DEFAULT_LATENCY_MS = 1.0;

    /** Default link capacity; generous enough to be non-binding in most designs. */
    public static final double DEFAULT_BANDWIDTH_MBPS = 1000.0;

    private final String id;
    private final String source;
    private final String target;
    private final String label;
    private final String protocol;
    private final double latencyMs;
    private final double bandwidthMbps;

    private CompiledEdge(Builder b) {
        this.id = b.id != null ? b.id : b.source + "->" + b.target;
        this.source = Objects.requireNonNull(b.source, "CompiledEdge.source must not be null");
        this.target = Objects.requireNonNull(b.target, "CompiledEdge.target must not be null");
        this.label = b.label;
        this.protocol = b.protocol == null ? "tcp" : b.protocol;
        this.latencyMs = b.latencyMs >= 0 ? b.latencyMs : DEFAULT_LATENCY_MS;
        this.bandwidthMbps = b.bandwidthMbps > 0 ? b.bandwidthMbps : DEFAULT_BANDWIDTH_MBPS;
    }

    public String getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public String getTarget() {
        return target;
    }

    public String getLabel() {
        return label;
    }

    public String getProtocol() {
        return protocol;
    }

    public double getLatencyMs() {
        return latencyMs;
    }

    public double getBandwidthMbps() {
        return bandwidthMbps;
    }

    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {
        private String id;
        private String source;
        private String target;
        private String label;
        private String protocol;
        private double latencyMs = -1;
        private double bandwidthMbps = -1;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder target(String target) {
            this.target = target;
            return this;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public Builder protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder latencyMs(double latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        public Builder bandwidthMbps(double bandwidthMbps) {
            this.bandwidthMbps = bandwidthMbps;
            return this;
        }

        public CompiledEdge build() {
            return new CompiledEdge(this);
        }
    }

    @Override
    public String toString() {
        return "CompiledEdge{" + source + "->" + target + "}";
    }
}
