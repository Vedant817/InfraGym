package com.archforge.design.canvas;

import com.archforge.contracts.canvas.Topology;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A topology as stored.
 *
 * <p>Nodes and edges are maps rather than the contract's own types, and that is deliberate.
 * The wire types are mutable POJOs that exist to absorb whatever React Flow sends, so mapping
 * them field by field would silently drop every field this service does not already know
 * about -- and the day the canvas grows a property, a stored design would lose it with nothing
 * to indicate that it had.
 *
 * <p>Converting through Jackson instead means the document is whatever the wire type actually
 * carries, at the time it was stored. The cost is that a malformed topology is stored as
 * whatever Jackson made of it rather than refused; that is the right trade here, because the
 * compiler is the component whose job is to reject a bad graph, and this service's job is to
 * remember what the user drew.
 *
 * <p>Positions are part of the document. Layout is what the user spent their time arranging,
 * so a design loaded without it is a design the user did not draw -- and a comparison between
 * two designs differing only in layout would treat them as identical.
 */
public record TopologySnapshot(
        String version,
        List<Map<String, Object>> nodes,
        List<Map<String, Object>> edges) {

    public TopologySnapshot {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static TopologySnapshot empty() {
        return new TopologySnapshot("1.0", List.of(), List.of());
    }

    /** Converts a wire topology into a stored snapshot, losslessly. */
    public static TopologySnapshot from(Topology topology, ObjectMapper objectMapper) {
        if (topology == null) {
            return empty();
        }
        return new TopologySnapshot("1.0", toMaps(topology.getNodes(), objectMapper),
                toMaps(topology.getEdges(), objectMapper));
    }

    private static List<Map<String, Object>> toMaps(List<?> values, ObjectMapper objectMapper) {
        List<Map<String, Object>> maps = new ArrayList<>();
        if (values == null) {
            return maps;
        }
        for (Object value : values) {
            Map<String, Object> map = objectMapper.convertValue(value, new com.fasterxml.jackson
                    .core.type.TypeReference<Map<String, Object>>() { });
            maps.add(new LinkedHashMap<>(map));
        }
        return maps;
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    /** Reads back into the wire type, for a submission event or a REST response. */
    public Topology toTopology(ObjectMapper objectMapper) {
        Topology topology = new Topology();
        topology.setNodes(toWire(nodes, objectMapper, com.archforge.contracts.canvas.CanvasNode.class));
        topology.setEdges(toWire(edges, objectMapper, com.archforge.contracts.canvas.CanvasEdge.class));
        return topology;
    }

    private static <T> List<T> toWire(List<Map<String, Object>> maps, ObjectMapper objectMapper,
                                      Class<T> type) {
        List<T> converted = new ArrayList<>();
        for (Map<String, Object> map : maps) {
            converted.add(objectMapper.convertValue(map, type));
        }
        return converted;
    }
}