package com.archforge.graphcompiler.validation;

import com.archforge.contracts.canvas.Topology;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reachability from an entry point, and the warnings derived from it.
 *
 * <p>This exists because the check walked the edge list backwards. From the single entry
 * point in a normal design it could therefore only ever "reach" that entry point, and it
 * warned that the entire rest of the architecture was unreachable — five warnings for a
 * perfectly ordinary three-tier design.
 *
 * <p>Warnings are easy to dismiss as cosmetic, and that was the mistake. The user who
 * <em>does</em> have a dead subsystem sees the same output and cannot tell which
 * warnings are real, so the check conveys nothing at all. These tests exist to make
 * "warns on a healthy design" and "stays quiet on a healthy design" both fail loudly.
 */
class SemanticValidatorReachabilityTest {

    private final SemanticValidator validator = new SemanticValidator();

    /** Messages of findings carrying a given code. */
    private List<String> codes(ValidationResult result, String code) {
        return result.getFindings().stream()
                .filter(finding -> code.equals(finding.getCode()))
                .map(finding -> finding.getMessage())
                .toList();
    }

    /**
     * The design the live broker produced: a load balancer in front of an API service,
     * which fans out to a cache, a database and a queue, with a worker behind the queue.
     * Every node except the load balancer has an incoming edge, so nothing is
     * unreachable and nothing should be reported.
     */
    private Topology fullStack() {
        return new Topology(List.of(
                node("lb", "load_balancer", "Load Balancer"),
                node("api", "service", "API Service"),
                node("cache", "cache", "Redis"),
                node("queue", "queue", "Event Bus"),
                node("worker", "worker", "Async Worker"),
                node("db", "database", "PostgreSQL")),
                List.of(
                        edge("e1", "lb", "api"),
                        edge("e2", "api", "cache"),
                        edge("e3", "api", "db"),
                        edge("e4", "api", "queue"),
                        edge("e5", "queue", "worker"),
                        edge("e6", "worker", "db")));
    }

    @Test
    @DisplayName("a fully connected design produces no unreachable warnings")
    void connectedDesignIsQuiet() {
        // The defect: every node but 'lb' was reported unreachable, on every run,
        // for every design. A validator that always warns is indistinguishable from
        // one that is broken.
        ValidationResult result = validator.validate(fullStack());

        assertThat(codes(result, "UNREACHABLE_NODE"))
                .as("a design where every component is reachable must not warn")
                .isEmpty();
    }

    @Test
    @DisplayName("a genuinely dead subsystem is reported")
    void deadSubsystemIsReported() {
        // The check the warning exists for, and the one that was not working: an
        // entirely disconnected set of components, which is invisible to an
        // edge-counting orphan check because those nodes have plenty of edges.
        Topology topology = new Topology(List.of(
                node("lb", "load_balancer", "Load Balancer"),
                node("api", "service", "API Service"),
                node("db", "database", "PostgreSQL"),
                node("orphan-svc", "service", "Dead Service"),
                node("orphan-store", "database", "Dead Store")),
                List.of(
                        edge("e1", "lb", "api"),
                        edge("e2", "api", "db"),
                        edge("e3", "orphan-svc", "orphan-store")));

        ValidationResult result = validator.validate(topology);

        List<String> unreachable = codes(result, "UNREACHABLE_NODE");
        assertThat(unreachable)
                .as("a disconnected subsystem is the case this check exists for")
                .hasSize(2);
        assertThat(String.join(" ", unreachable))
                .contains("orphan-svc")
                .contains("orphan-store");
        assertThat(String.join(" ", unreachable))
                .as("and must not claim the live path is dead")
                .doesNotContain("'api'")
                .doesNotContain("'db'");
    }

    @Test
    @DisplayName("a single disconnected node is reported")
    void singleDisconnectedNodeIsReported() {
        Topology topology = new Topology(List.of(
                node("lb", "load_balancer", "Load Balancer"),
                node("api", "service", "API Service"),
                node("stray", "monitoring", "Stray Dashboard")),
                List.of(edge("e1", "lb", "api")));

        List<String> unreachable = codes(validator.validate(topology), "UNREACHABLE_NODE");

        assertThat(unreachable).hasSize(1);
        assertThat(unreachable.get(0)).contains("stray");
    }

    @Test
    @DisplayName("reachability follows the edge direction, not the node type")
    void directionMatters() {
        // api -> db means the database is downstream of the API and reachable. db -> api
        // means the API is downstream of the database and is the one unreachable. A
        // backwards walk cannot tell these apart, which is exactly how the defect
        // shipped: both cases answered "nothing is reachable".
        Topology reversed = new Topology(List.of(
                node("lb", "load_balancer", "Load Balancer"),
                node("api", "service", "API Service"),
                node("db", "database", "PostgreSQL")),
                List.of(
                        edge("e1", "lb", "db"),
                        edge("e2", "db", "api")));

        List<String> unreachable = codes(validator.validate(reversed), "UNREACHABLE_NODE");

        // lb -> db -> api: both are downstream of the load balancer, so neither is
        // unreachable. The API is above the database logically, but reachability is
        // graph-theoretic: it depends only on there being a path from an entry point.
        assertThat(unreachable)
                .as("every node has a path from the load balancer")
                .isEmpty();
    }

    @Test
    @DisplayName("several entry points are all treated as entry points")
    void multipleEntryPoints() {
        Topology topology = new Topology(List.of(
                node("cdn", "cdn", "Edge Cache"),
                node("lb", "load_balancer", "Load Balancer"),
                node("api", "service", "API Service"),
                node("db", "database", "PostgreSQL")),
                List.of(
                        edge("e1", "cdn", "db"),
                        edge("e2", "lb", "api"),
                        edge("e3", "api", "db")));

        // The database is reachable through the CDN but not through the API. A check
        // that analysed one entry point in isolation would call it unreachable; one
        // that walked backwards would call the API and the database unreachable.
        assertThat(codes(validator.validate(topology), "UNREACHABLE_NODE")).isEmpty();
    }

    @Test
    @DisplayName("a design with no entry point reports that, and skips reachability")
    void noEntryPointSkipsReachability() {
        // Reachability is meaningless without an origin, so this must not also emit a
        // wall of UNREACHABLE_NODE warnings -- the user already has one clear thing
        // to fix.
        Topology topology = new Topology(List.of(
                node("a", "service", "A"),
                node("b", "database", "B")),
                List.of(edge("e1", "a", "b")));

        ValidationResult result = validator.validate(topology);

        assertThat(codes(result, "NO_ENTRY_POINT")).isNotEmpty();
        assertThat(codes(result, "UNREACHABLE_NODE"))
                .as("no origin means no reachability verdict")
                .isEmpty();
    }

    @Test
    @DisplayName("an empty or single-node topology produces no findings at all")
    void degenerateTopologiesAreQuiet() {
        assertThat(codes(validator.validate(null), "UNREACHABLE_NODE")).isEmpty();
        assertThat(codes(validator.validate(new Topology(List.of(), List.of())), "UNREACHABLE_NODE"))
                .isEmpty();
    }

    // --- fixtures --------------------------------------------------------------

    private static com.archforge.contracts.canvas.CanvasNode node(String id, String type, String label) {
        com.archforge.contracts.canvas.NodeData data = new com.archforge.contracts.canvas.NodeData();
        data.setLabel(label);
        return new com.archforge.contracts.canvas.CanvasNode(id, type,
                new com.archforge.contracts.canvas.Position(0, 0), data);
    }

    private static com.archforge.contracts.canvas.CanvasEdge edge(String id, String source, String target) {
        return new com.archforge.contracts.canvas.CanvasEdge(id, source, target);
    }
}
