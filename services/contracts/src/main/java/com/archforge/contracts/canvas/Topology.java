package com.archforge.contracts.canvas;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The complete canvas document: nodes plus edges, in the shape React Flow emits.
 *
 * <p>This is the unit of work across the whole platform:
 * <pre>
 *   frontend  --design.submissions-->  graph-compiler  --simulation.tasks-->
 *   simulation-engine  --simulation.telemetry-->  telemetry-aggregator
 *   --aggregated.metrics-->  ai-evaluator  --evaluation.results-->  design-service
 * </pre>
 *
 * <p>Mutable with defensive copying in {@link #toBuilder()}-free setters because
 * Jackson needs no-arg constructors, but every accessor hands back an
 * unmodifiable view so a caller cannot mutate a topology another stage is
 * reading.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Topology implements Serializable {

    private List<CanvasNode> nodes = new ArrayList<>();
    private List<CanvasEdge> edges = new ArrayList<>();

    public Topology() {
    }

    public Topology(List<CanvasNode> nodes, List<CanvasEdge> edges) {
        setNodes(nodes);
        setEdges(edges);
    }

    public static Topology empty() {
        return new Topology();
    }

    public List<CanvasNode> getNodes() {
        return nodes == null ? List.of() : List.copyOf(nodes);
    }

    public void setNodes(List<CanvasNode> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);
    }

    public List<CanvasEdge> getEdges() {
        return edges == null ? List.of() : List.copyOf(edges);
    }

    public void setEdges(List<CanvasEdge> edges) {
        this.edges = edges == null ? new ArrayList<>() : new ArrayList<>(edges);
    }

    public int nodeCount() {
        return nodes == null ? 0 : nodes.size();
    }

    public int edgeCount() {
        return edges == null ? 0 : edges.size();
    }

    public boolean isEmpty() {
        return nodeCount() == 0;
    }

    public Optional<CanvasNode> node(String id) {
        if (nodes == null || id == null) {
            return Optional.empty();
        }
        return nodes.stream().filter(n -> id.equals(n.getId())).findFirst();
    }

    /** Node ids in declaration order; the basis for deterministic iteration. */
    public List<String> nodeIds() {
        if (nodes == null) {
            return List.of();
        }
        return nodes.stream().map(CanvasNode::getId).filter(java.util.Objects::nonNull).toList();
    }

    /** Index by id for O(1) lookup in the compiler's hot loops. */
    public Map<String, CanvasNode> indexById() {
        Map<String, CanvasNode> index = new LinkedHashMap<>();
        if (nodes != null) {
            for (CanvasNode node : nodes) {
                if (node != null && node.getId() != null) {
                    index.put(node.getId(), node);
                }
            }
        }
        return index;
    }

    /**
     * Type of a node, resolved leniently. Returns {@code null} for an unknown
     * type so the validator — not this accessor — decides how to report it.
     */
    public NodeType resolveType(CanvasNode node) {
        return node == null ? null : NodeType.findByValue(node.getType());
    }

    @Override
    public String toString() {
        return "Topology{nodes=" + nodeCount() + ", edges=" + edgeCount() + "}";
    }
}
