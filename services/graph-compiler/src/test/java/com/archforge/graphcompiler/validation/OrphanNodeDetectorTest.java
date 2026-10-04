package com.archforge.graphcompiler.validation;

import com.archforge.contracts.canvas.Topology;
import com.archforge.graphcompiler.support.TestTopologies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link OrphanNodeDetector}.
 *
 * <p>Regression coverage for the false negative: both endpoints of every edge were added
 * to the "connected" set without checking that either endpoint was a real node, so a node
 * reachable only through a non-existent node was reported as fine. Edges to missing
 * nodes no longer make a real orphan look connected, and self-loops no longer count as
 * connectivity.
 */
class OrphanNodeDetectorTest {

    private final OrphanNodeDetector detector = new OrphanNodeDetector();

    @Test
    @DisplayName("reports a node with no edges")
    void reportsFullyDisconnectedNode() {
        ValidationResult result = detector.validate(TestTopologies.disconnected());

        assertTrue(codesOf(result).contains("ORPHAN_NODE"), "got " + codesOf(result));
        assertTrue(messagesOf(result).stream().anyMatch(m -> m.contains("stray")),
                "the 'stray' node must be named, got " + messagesOf(result));
    }

    @Test
    @DisplayName("reports nothing for a fully connected design")
    void reportsNothingWhenConnected() {
        ValidationResult result = detector.validate(TestTopologies.validThreeTier());

        assertFalse(codesOf(result).contains("ORPHAN_NODE"),
                "a connected design has no orphans; got " + messagesOf(result));
    }

    @Test
    @DisplayName("a dangling edge does not make an orphan look connected")
    void danglingEdgeDoesNotRescueAnOrphan() {
        // Regression: nodes {a, lonely}; edges a->ghost and ghost->lonely. `lonely` is
        // reachable only through a node that does not exist, so it is an orphan. The old
        // implementation added both endpoints unconditionally and reported zero findings.
        Topology topology = new Topology(
                List.of(
                        TestTopologies.node("a", "service", "A"),
                        TestTopologies.node("lonely", "service", "Lonely")),
                List.of(
                        TestTopologies.edge("e1", "a", "ghost"),
                        TestTopologies.edge("e2", "ghost", "lonely")));

        ValidationResult result = detector.validate(topology);

        assertTrue(messagesOf(result).stream().anyMatch(m -> m.contains("lonely")),
                "lonely must be reported as an orphan; got " + messagesOf(result));
    }

    @Test
    @DisplayName("a self-loop does not count as connectivity")
    void selfLoopIsNotConnectivity() {
        // Regression: the previous implementation added both endpoints of every edge to
        // the connected set unconditionally, so node `selfish` looked connected to itself
        // and was never reported.
        Topology topology = new Topology(
                List.of(
                        TestTopologies.node("selfish", "service", "Selfish"),
                        TestTopologies.node("lonely", "service", "Lonely")),
                List.of(TestTopologies.edge("e-self", "selfish", "selfish")));

        ValidationResult result = detector.validate(topology);

        assertTrue(messagesOf(result).stream().anyMatch(m -> m.contains("selfish")),
                "a node whose only edge is a self-loop is an orphan; got " + messagesOf(result));
        assertTrue(messagesOf(result).stream().anyMatch(m -> m.contains("lonely")),
                "a node with no edges at all is an orphan; got " + messagesOf(result));
    }

    @Test
    @DisplayName("two nodes in one component are not orphans")
    void twoNodeComponentIsConnected() {
        Topology topology = new Topology(
                List.of(
                        TestTopologies.node("lb", "load_balancer", "LB"),
                        TestTopologies.node("api", "service", "API")),
                List.of(TestTopologies.edge("e1", "lb", "api")));

        ValidationResult result = detector.validate(topology);

        assertFalse(codesOf(result).contains("ORPHAN_NODE"), "got " + messagesOf(result));
    }

    private List<String> codesOf(ValidationResult result) {
        return result.getFindings().stream().map(ValidationError::getCode).toList();
    }

    private List<String> messagesOf(ValidationResult result) {
        return result.getFindings().stream().map(ValidationError::getMessage).toList();
    }
}
