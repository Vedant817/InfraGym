package com.archforge.contracts.graph;

import com.archforge.contracts.canvas.NodeType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A node after compilation: type-resolved, dependency-annotated, enriched with
 * simulation characteristics and stamped with a topological rank.
 *
 * <p>Immutable. The builder normalises nulls to empty collections and rejects a
 * null id or negative rank, so downstream stages (simulator, aggregator,
 * evaluator) never have to null-check the graph they walk.
 *
 * <p>{@code dependencies} are node ids that must complete before this node may
 * be entered; {@code dependents} is the reverse index. Both are computed by the
 * compiler from the edge set so the simulator does not rebuild the adjacency on
 * every run.
 *
 * <h2>Jackson</h2>
 * {@link JsonDeserialize}{@code (builder = CompiledNode.Builder.class)} is required:
 * the only constructor is private and takes a {@link Builder}, so without it Jackson
 * reports {@code no Creators} and every consumer fails to read the node.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(builder = CompiledNode.Builder.class)
public final class CompiledNode implements Serializable {

    private final String id;
    private final NodeType type;
    private final String label;
    private final Set<String> dependencies;
    private final Set<String> dependents;
    private final Map<String, Object> simulationMetadata;
    private final Map<String, Double> capacity;
    private final Map<String, Double> latencyProfile;
    private final Map<String, Double> throughput;
    private final Set<String> failureModes;
    private final long recoveryTimeMs;
    private final int topologicalOrder;

    private CompiledNode(Builder b) {
        this.id = Objects.requireNonNull(b.id, "CompiledNode.id must not be null");
        this.type = Objects.requireNonNull(b.type, "CompiledNode.type must not be null for node " + b.id);
        this.label = b.label == null || b.label.isBlank() ? b.id : b.label;
        this.dependencies = Collections.unmodifiableSet(new LinkedHashSet<>(b.dependencies));
        this.dependents = Collections.unmodifiableSet(new LinkedHashSet<>(b.dependents));
        this.simulationMetadata = Collections.unmodifiableMap(new LinkedHashMap<>(b.simulationMetadata));
        this.capacity = Collections.unmodifiableMap(new LinkedHashMap<>(b.capacity));
        this.latencyProfile = Collections.unmodifiableMap(new LinkedHashMap<>(b.latencyProfile));
        this.throughput = Collections.unmodifiableMap(new LinkedHashMap<>(b.throughput));
        this.failureModes = Collections.unmodifiableSet(new LinkedHashSet<>(b.failureModes));
        this.recoveryTimeMs = Math.max(0, b.recoveryTimeMs);
        if (b.topologicalOrder < 0) {
            throw new IllegalArgumentException(
                    "CompiledNode.topologicalOrder must be >= 0 but was " + b.topologicalOrder + " for node " + b.id);
        }
        this.topologicalOrder = b.topologicalOrder;
    }

    public String getId() {
        return id;
    }

    public NodeType getType() {
        return type;
    }

    public String getLabel() {
        return label;
    }

    public Set<String> getDependencies() {
        return dependencies;
    }

    public Set<String> getDependents() {
        return dependents;
    }

    /** Full enrichment blob from the compiler; opaque to everything downstream. */
    public Map<String, Object> getSimulationMetadata() {
        return simulationMetadata;
    }

    /** Hard limits, e.g. {@code requestsPerSecond}, {@code iops}, {@code partitions}. */
    public Map<String, Double> getCapacity() {
        return capacity;
    }

    /** Latency percentiles in milliseconds: {@code p50Ms}, {@code p95Ms}, {@code p99Ms}. */
    public Map<String, Double> getLatencyProfile() {
        return latencyProfile;
    }

    /** Sustainable rates per second, keyed by unit-bearing names. */
    public Map<String, Double> getThroughput() {
        return throughput;
    }

    /** Failure modes this node type can exhibit; used to build failure plans. */
    public Set<String> getFailureModes() {
        return failureModes;
    }

    public long getRecoveryTimeMs() {
        return recoveryTimeMs;
    }

    /** Zero-based rank from the topological sort; the simulator walks nodes in this order. */
    public int getTopologicalOrder() {
        return topologicalOrder;
    }

    public boolean isCompute() {
        return type.isCompute();
    }

    public boolean isStorage() {
        return type.isStorage();
    }

    public boolean isMessaging() {
        return type.isMessaging();
    }

    /** Convenience lookup across the merged capacity/throughput maps. */
    public double capacityOrDefault(String key, double fallback) {
        Double value = capacity.get(key);
        if (value == null) {
            value = throughput.get(key);
        }
        return value == null ? fallback : value;
    }

    public double latencyOrDefault(String key, double fallback) {
        Double value = latencyProfile.get(key);
        return value == null ? fallback : value;
    }

    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {
        private String id;
        private NodeType type;
        private String label;
        private Set<String> dependencies = new HashSet<>();
        private Set<String> dependents = new HashSet<>();
        private Map<String, Object> simulationMetadata = new HashMap<>();
        private Map<String, Double> capacity = new HashMap<>();
        private Map<String, Double> latencyProfile = new HashMap<>();
        private Map<String, Double> throughput = new HashMap<>();
        private Set<String> failureModes = new HashSet<>();
        private long recoveryTimeMs;
        private int topologicalOrder;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder type(NodeType type) {
            this.type = type;
            return this;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public Builder dependencies(Set<String> dependencies) {
            this.dependencies = dependencies == null ? new HashSet<>() : new HashSet<>(dependencies);
            return this;
        }

        public Builder dependents(Set<String> dependents) {
            this.dependents = dependents == null ? new HashSet<>() : new HashSet<>(dependents);
            return this;
        }

        public Builder simulationMetadata(Map<String, Object> simulationMetadata) {
            this.simulationMetadata = simulationMetadata == null ? new HashMap<>() : new HashMap<>(simulationMetadata);
            return this;
        }

        public Builder capacity(Map<String, Double> capacity) {
            this.capacity = capacity == null ? new HashMap<>() : new HashMap<>(capacity);
            return this;
        }

        public Builder latencyProfile(Map<String, Double> latencyProfile) {
            this.latencyProfile = latencyProfile == null ? new HashMap<>() : new HashMap<>(latencyProfile);
            return this;
        }

        public Builder throughput(Map<String, Double> throughput) {
            this.throughput = throughput == null ? new HashMap<>() : new HashMap<>(throughput);
            return this;
        }

        public Builder failureModes(Set<String> failureModes) {
            this.failureModes = failureModes == null ? new HashSet<>() : new HashSet<>(failureModes);
            return this;
        }

        public Builder recoveryTimeMs(long recoveryTimeMs) {
            this.recoveryTimeMs = recoveryTimeMs;
            return this;
        }

        public Builder topologicalOrder(int topologicalOrder) {
            this.topologicalOrder = topologicalOrder;
            return this;
        }

        public CompiledNode build() {
            return new CompiledNode(this);
        }
    }

    @Override
    public String toString() {
        return "CompiledNode{" + id + ":" + type.getValue() + "@" + topologicalOrder + "}";
    }
}
