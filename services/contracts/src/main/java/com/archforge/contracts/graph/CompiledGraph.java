package com.archforge.contracts.graph;

import com.archforge.contracts.canvas.NodeType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The compiled DAG handed from the graph compiler to the simulation engine.
 *
 * <p>Immutable and self-describing: it carries the node list, the edge list,
 * graph-level metrics, and the warnings raised during compilation. The simulator
 * treats it as read-only input and never mutates it.
 *
 * <p>{@code warnings} matter as much as {@code nodes}. A design that compiles
 * but has orphaned components or no entry point is exactly the kind of thing the
 * AI evaluator should be told about, so warnings travel with the graph rather
 * than being logged and lost.
 *
 * <h2>Jackson</h2>
 * {@link JsonDeserialize}{@code (builder = CompiledGraph.Builder.class)} is
 * <b>load-bearing, and its absence was fatal</b>. The only constructor is private and
 * takes a {@link Builder}, which Jackson cannot use as a creator, so every consumer of
 * {@code simulation.tasks} threw
 * {@code InvalidDefinitionException: no Creators, like default constructor, exist} on
 * the first record it received. That exception was swallowed by a catch-and-log, so the
 * service stayed healthy, the topic stayed empty, and the entire simulation pipeline was
 * dead with no symptom other than an absent log line.
 *
 * <p>{@link CompiledNode} and {@link CompiledEdge} carry the same annotation for the
 * same reason, and every wire type in this package must keep it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(builder = CompiledGraph.Builder.class)
public final class CompiledGraph implements Serializable {


    private final List<CompiledNode> nodes;
    private final List<CompiledEdge> edges;
    private final Map<String, Object> graphMetadata;
    private final String name;
    private final String description;
    private final List<String> warnings;
    private final Instant compiledAt;

    private CompiledGraph(Builder b) {
        Objects.requireNonNull(b.nodes, "CompiledGraph.nodes must not be null");
        this.nodes = Collections.unmodifiableList(new ArrayList<>(b.nodes));
        this.edges = Collections.unmodifiableList(new ArrayList<>(b.edges == null ? List.of() : b.edges));
        this.graphMetadata = Collections.unmodifiableMap(
                new LinkedHashMap<>(b.graphMetadata == null ? Map.of() : b.graphMetadata));
        this.name = b.name == null ? "Untitled architecture" : b.name;
        this.description = b.description == null ? "" : b.description;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(b.warnings == null ? List.of() : b.warnings));
        this.compiledAt = b.compiledAt == null ? Instant.now() : b.compiledAt;
    }

    public List<CompiledNode> getNodes() {
        return nodes;
    }

    public List<CompiledEdge> getEdges() {
        return edges;
    }

    /** Graph-level figures: critical path, fan-in/out, depth, bottleneck hints. */
    public Map<String, Object> getGraphMetadata() {
        return graphMetadata;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public Instant getCompiledAt() {
        return compiledAt;
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public Optional<CompiledNode> node(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return nodes.stream().filter(n -> id.equals(n.getId())).findFirst();
    }

    public List<CompiledNode> nodesOfType(NodeType type) {
        return nodes.stream().filter(n -> n.getType() == type).toList();
    }

    /** Nodes in topological order, i.e. the order the simulator may evaluate them in. */
    public List<CompiledNode> inTopologicalOrder() {
        List<CompiledNode> ordered = new ArrayList<>(nodes);
        ordered.sort(java.util.Comparator.comparingInt(CompiledNode::getTopologicalOrder));
        return Collections.unmodifiableList(ordered);
    }

    /** Adjacency keyed by source id; built once per simulation run. */
    public Map<String, List<String>> outgoing() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (CompiledNode node : nodes) {
            map.put(node.getId(), new ArrayList<>(node.getDependents()));
        }
        return map;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Tells Jackson to use the fluent {@code nodes(...)}-style setters with no prefix.
     *
     * <p>Without this Jackson looks for {@code withNodes(...)}; with it, the builder style
     * used throughout this codebase is what gets used.
     */
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {
        private List<CompiledNode> nodes = new ArrayList<>();
        private List<CompiledEdge> edges;
        private Map<String, Object> graphMetadata;
        private String name;
        private String description;
        private List<String> warnings;
        private Instant compiledAt;

        public Builder nodes(List<CompiledNode> nodes) {
            this.nodes = nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);
            return this;
        }

        public Builder edges(List<CompiledEdge> edges) {
            this.edges = edges == null ? new ArrayList<>() : new ArrayList<>(edges);
            return this;
        }

        public Builder graphMetadata(Map<String, Object> graphMetadata) {
            this.graphMetadata = graphMetadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(graphMetadata);
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder warnings(List<String> warnings) {
            this.warnings = warnings == null ? new ArrayList<>() : new ArrayList<>(warnings);
            return this;
        }

        public Builder compiledAt(Instant compiledAt) {
            this.compiledAt = compiledAt;
            return this;
        }

        public CompiledGraph build() {
            return new CompiledGraph(this);
        }
    }

    @Override
    public String toString() {
        return "CompiledGraph{" + name + ", nodes=" + nodes.size() + ", edges=" + edges.size() + "}";
    }
}
