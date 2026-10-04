package com.archforge.graphcompiler.validation;

import com.archforge.contracts.canvas.CanvasNode;
import com.archforge.contracts.canvas.Topology;
import com.archforge.graphcompiler.support.TestTopologies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SchemaValidator}.
 *
 * <p>Regression coverage for the constructor-injection defect: the bounds used to be
 * {@code @Value} fields, so a directly constructed instance had {@code maxNodes == 0}
 * and rejected every topology with "exceeds maximum of 0 nodes". Four of the previous
 * suite's own tests failed for exactly this reason. These tests construct the validator
 * directly, which is only safe now that the limits are constructor arguments.
 */
class SchemaValidatorTest {

    private SchemaValidator validator() {
        return new SchemaValidator(500, 2000, 4096);
    }

    @Test
    @DisplayName("accepts a valid topology")
    void acceptsValidTopology() {
        ValidationResult result = validator().validate(TestTopologies.validThreeTier());

        assertFalse(result.hasErrors(), "valid topology produced errors: " + result.getErrorMessages());
    }

    @Test
    @DisplayName("is usable when constructed directly, outside Spring")
    void worksOutsideSpring() {
        // The previous implementation had maxNodes=0 here and rejected everything.
        ValidationResult result = validator().validate(TestTopologies.validThreeTier());

        assertEquals(0, result.errorCount(),
                "a directly constructed validator must use its declared defaults, not zero");
    }

    @Test
    @DisplayName("rejects a null topology")
    void rejectsNull() {
        assertTrue(validator().validate(null).hasErrors());
    }

    @Test
    @DisplayName("rejects an empty topology")
    void rejectsEmpty() {
        ValidationResult result = validator().validate(Topology.empty());

        assertTrue(result.hasErrors());
        assertTrue(codesOf(result).contains("EMPTY_TOPOLOGY"));
    }

    @Test
    @DisplayName("rejects duplicate node ids")
    void rejectsDuplicateNodeIds() {
        ValidationResult result = validator().validate(TestTopologies.withDuplicateNodeIds());

        assertTrue(codesOf(result).contains("DUPLICATE_NODE_ID"), "got " + codesOf(result));
    }

    @Test
    @DisplayName("rejects a node with no id")
    void rejectsMissingNodeId() {
        CanvasNode noId = new CanvasNode();
        noId.setType("service");
        noId.setData(new com.archforge.contracts.canvas.NodeData("Nameless"));

        ValidationResult result = validator().validate(new Topology(List.of(noId), List.of()));

        assertTrue(codesOf(result).contains("MISSING_NODE_ID"), "got " + codesOf(result));
    }

    @Test
    @DisplayName("rejects too many nodes")
    void rejectsTooManyNodes() {
        SchemaValidator strict = new SchemaValidator(3, 2000, 4096);

        ValidationResult result = strict.validate(TestTopologies.validThreeTier());

        assertTrue(codesOf(result).contains("TOO_MANY_NODES"), "got " + codesOf(result));
    }

    @Test
    @DisplayName("accepts a topology exactly at the node limit")
    void acceptsExactlyAtNodeLimit() {
        SchemaValidator strict = new SchemaValidator(4, 2000, 4096);

        assertFalse(strict.validate(TestTopologies.validThreeTier()).hasErrors(),
                "a topology exactly at the limit must be accepted, not rejected");
    }

    @Test
    @DisplayName("rejects too many edges")
    void rejectsTooManyEdges() {
        SchemaValidator strict = new SchemaValidator(500, 1, 4096);

        ValidationResult result = strict.validate(TestTopologies.validThreeTier());

        assertTrue(codesOf(result).contains("TOO_MANY_EDGES"), "got " + codesOf(result));
    }

    @Test
    @DisplayName("bounds the per-node config size")
    void boundsConfigSize() {
        // Regression: node and edge counts were capped but node.data.config was not, so a
        // legal topology could serialise to a payload exceeding the producer's limit.
        Map<String, Object> oversized = IntStream.range(0, 500)
                .boxed()
                .collect(java.util.stream.Collectors.toMap(
                        i -> "setting" + i,
                        i -> "a-fairly-long-value-" + i));

        Topology topology = new Topology(
                List.of(TestTopologies.node("a", "service", "A", oversized)),
                List.of());

        ValidationResult result = validator().validate(topology);

        assertTrue(codesOf(result).contains("CONFIG_TOO_LARGE")
                        || codesOf(result).contains("CONFIG_VALUE_TOO_LARGE"),
                "an oversized config must be rejected, got " + codesOf(result));
    }

    @Test
    @DisplayName("accepts a node whose parent is declared later")
    void acceptsForwardParentReference() {
        // The previous version checked parentId against nodeIds accumulated so far, so a
        // child listed before its parent was reported as referencing a missing node.
        CanvasNode parent = TestTopologies.node("parent", "service", "Parent");
        CanvasNode child = TestTopologies.node("child", "service", "Child");
        child.setParentId("parent");

        ValidationResult result = validator().validate(new Topology(List.of(child, parent), List.of()));

        assertFalse(codesOf(result).contains("INVALID_PARENT_ID"),
                "a forward parent reference is legal; got " + codesOf(result));
    }

    @Test
    @DisplayName("rejects a genuinely missing parent")
    void rejectsMissingParent() {
        CanvasNode child = TestTopologies.node("child", "service", "Child");
        child.setParentId("ghost");

        ValidationResult result = validator().validate(new Topology(List.of(child), List.of()));

        assertTrue(codesOf(result).contains("INVALID_PARENT_ID"), "got " + codesOf(result));
    }

    @Test
    @DisplayName("returns snapshots, not a live view")
    void returnsSnapshots() {
        ValidationResult result = validator().validate(TestTopologies.validThreeTier());
        List<String> before = result.getErrorMessages();

        // Appending must not retroactively change an already-returned snapshot.
        assertEquals(before, result.getErrorMessages(),
                "getErrorMessages must return an immutable snapshot");
    }

    private List<String> codesOf(ValidationResult result) {
        return result.getFindings().stream().map(ValidationError::getCode).toList();
    }
}
