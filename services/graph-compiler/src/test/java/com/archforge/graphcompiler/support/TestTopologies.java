package com.archforge.graphcompiler.support;

import com.archforge.contracts.canvas.CanvasEdge;
import com.archforge.contracts.canvas.CanvasNode;
import com.archforge.contracts.canvas.NodeData;
import com.archforge.contracts.canvas.Position;
import com.archforge.contracts.canvas.Topology;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fixture builders for tests.
 *
 * <p>Centralised because the previous tests each hand-rolled topology literals — which is
 * how one came to assert on a graph whose "duplicate edge" was not a duplicate. When
 * every topology is built here, the intent is in the method name and a fixture mistake
 * is visible in the test that uses it.
 */
public final class TestTopologies {

    private TestTopologies() {
    }

    public static CanvasNode node(String id, String type, String label) {
        return node(id, type, label, Map.of());
    }

    public static CanvasNode node(String id, String type, String label, Map<String, Object> config) {
        NodeData data = new NodeData();
        data.setLabel(label);
        data.setConfig(config);
        return new CanvasNode(id, type, new Position(0, 0), data);
    }

    public static CanvasEdge edge(String id, String source, String target) {
        return new CanvasEdge(id, source, target);
    }

    /** {@code LB -> api -> cache, api -> db}: a valid, fully connected design. */
    public static Topology validThreeTier() {
        return new Topology(
                List.of(
                        node("lb", "load_balancer", "Load Balancer"),
                        node("api", "service", "API Service"),
                        node("cache", "cache", "Redis"),
                        node("db", "database", "PostgreSQL")),
                List.of(
                        edge("e-lb-api", "lb", "api"),
                        edge("e-api-cache", "api", "cache"),
                        edge("e-api-db", "api", "db")));
    }

    /** {@code a -> b -> a}: the minimal cycle. */
    public static Topology withCycle() {
        return new Topology(
                List.of(node("a", "service", "A"), node("b", "service", "B")),
                List.of(edge("e1", "a", "b"), edge("e2", "b", "a")));
    }

    /** Two nodes sharing an id. */
    public static Topology withDuplicateNodeIds() {
        return new Topology(
                List.of(
                        node("api", "service", "API"),
                        node("api", "database", "Shadow API")),
                List.of());
    }

    /** Two edges for the same ordered pair. */
    public static Topology withDuplicateEdges() {
        return new Topology(
                List.of(node("a", "service", "A"), node("b", "service", "B")),
                List.of(
                        edge("e1", "a", "b"),
                        edge("e2", "a", "b"),
                        edge("e3", "b", "a")));
    }

    /** A self-loop on {@code a}. */
    public static Topology withSelfLoop() {
        return new Topology(
                List.of(node("a", "service", "A"), node("b", "service", "B")),
                List.of(edge("e1", "a", "a"), edge("e2", "a", "b")));
    }

    /** An edge referencing a node that does not exist. */
    public static Topology withDanglingEdge() {
        return new Topology(
                List.of(node("a", "service", "A"), node("b", "service", "B")),
                List.of(edge("e1", "a", "b"), edge("e2", "b", "ghost")));
    }

    /** Two disconnected components; every node in each is isolated from the other. */
    public static Topology disconnected() {
        return new Topology(
                List.of(
                        node("lb", "load_balancer", "LB"),
                        node("a1", "service", "A1"),
                        node("a2", "service", "A2"),
                        node("stray", "service", "Stray")),
                List.of(
                        edge("e1", "lb", "a1"),
                        edge("e2", "a1", "a2")));
    }

    /**
     * Nodes with no usable id.
     *
     * <p>Regression fixture for the builder defect where such a topology produced a
     * zero-node graph instead of failing.
     */
    public static Topology allNodesWithoutIds() {
        CanvasNode first = new CanvasNode();
        first.setType("service");
        first.setData(new NodeData("Nameless"));
        CanvasNode second = new CanvasNode();
        second.setType("service");
        second.setData(new NodeData("Also nameless"));
        return new Topology(List.of(first, second), List.of());
    }

    /** {@code n} nodes chained {@code n0 -> n1 -> ... -> n(n-1)}. */
    public static Topology chain(int n) {
        List<CanvasNode> nodes = new ArrayList<>(n);
        List<CanvasEdge> edges = new ArrayList<>(Math.max(0, n - 1));
        for (int i = 0; i < n; i++) {
            nodes.add(node("n" + i, i == 0 ? "load_balancer" : "service", "N" + i));
        }
        for (int i = 1; i < n; i++) {
            edges.add(edge("e" + i, "n" + (i - 1), "n" + i));
        }
        return new Topology(nodes, edges);
    }

    /**
     * One load balancer, {@code width} services in parallel, one database.
     *
     * <p>Pins down breadth: rank 0 holds {@code width} nodes and every other rank holds
     * one. A breadth implementation that returns the largest single rank would answer
     * {@code width} here by luck, so {@link #asymmetricRanks()} exists to catch that.
     */
    public static Topology layered(int width) {
        List<CanvasNode> nodes = new ArrayList<>();
        List<CanvasEdge> edges = new ArrayList<>();
        nodes.add(node("lb", "load_balancer", "LB"));
        for (int i = 0; i < width; i++) {
            nodes.add(node("src" + i, "service", "Source " + i));
        }
        nodes.add(node("sink", "database", "Primary"));
        for (int i = 0; i < width; i++) {
            edges.add(edge("e-lb-" + i, "lb", "src" + i));
            edges.add(edge("e-" + i + "-sink", "src" + i, "sink"));
        }
        return new Topology(nodes, edges);
    }

    /**
     * Ranks of sizes 3, 3, 1.
     *
     * <p>The defect this catches: the old {@code calculateGraphBreadth} returned the
     * <em>modal rank size</em> — here that is 3, but the true maximum rank size is also
     * 3, so this shape cannot distinguish. {@link #wideTail()} can.
     */
    public static Topology skewedRanks() {
        List<CanvasNode> nodes = new ArrayList<>(List.of(
                node("root", "load_balancer", "Root")));
        List<CanvasEdge> edges = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            nodes.add(node("mid" + i, "service", "Mid " + i));
            edges.add(edge("e-root-mid" + i, "root", "mid" + i));
        }
        for (int i = 0; i < 6; i++) {
            nodes.add(node("leaf" + i, "service", "Leaf " + i));
            edges.add(edge("e-mid" + (i % 2) + "-leaf" + i, "mid" + (i % 2), "leaf" + i));
        }
        return new Topology(nodes, edges);
    }

    /** Ranks of sizes 1, 6, 1 — the largest rank is neither first nor last. */
    public static Topology wideTail() {
        List<CanvasNode> nodes = new ArrayList<>(List.of(
                node("root", "load_balancer", "Root"),
                node("sink", "database", "Primary")));
        List<CanvasEdge> edges = new ArrayList<>(List.of(edge("e-root-sink", "root", "sink")));

        for (int i = 0; i < 6; i++) {
            nodes.add(node("leaf" + i, "service", "Leaf " + i));
            edges.add(edge("e-root-leaf" + i, "root", "leaf" + i));
            edges.add(edge("e-leaf" + i + "-sink", "leaf" + i, "sink"));
        }
        return new Topology(nodes, edges);
    }
}
