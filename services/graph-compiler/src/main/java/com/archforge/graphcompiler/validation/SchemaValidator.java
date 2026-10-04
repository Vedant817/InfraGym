package com.archforge.graphcompiler.validation;

import com.archforge.contracts.canvas.CanvasNode;
import com.archforge.contracts.canvas.Topology;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Structural validation of a submitted topology.
 *
 * <p><b>Why the limits are constructor arguments rather than {@code @Value} fields.</b>
 * The previous version injected them with field-level {@code @Value}, which Spring
 * populates at context refresh but which leaves them at the primitive default of
 * {@code 0} for any instance constructed directly. A plain {@code new
 * SchemaValidator()} therefore rejected <em>every</em> input with "exceeds maximum of
 * 0 nodes" — which is why four of its own unit tests failed, and which would have
 * silently rejected 100% of traffic the moment anything started constructing this
 * outside a Spring context.
 *
 * <p>Constructor injection with defaults makes the class correct in both worlds: it
 * is fully functional as a plain object in a test, and Spring overrides the bounds
 * from configuration in production.
 *
 * <p>Also bounded here, where it previously was not: the size of
 * {@code node.data.config}. Node and edge counts were capped at 500/2000, but each
 * node's config map was copied verbatim into the enriched graph and then into a Kafka
 * message, so a legal 500-node topology carrying a few config keys each produced
 * ~460 KB — and a few kilobytes per node would exceed the producer's request limit and
 * lose the task silently.
 */
@Component
public class SchemaValidator {

    /** Default per-node config budget, in bytes of serialised JSON. */
    static final int DEFAULT_MAX_CONFIG_BYTES_PER_NODE = 4096;

    private static final int MAX_ID_LENGTH = 128;
    private static final int MAX_LABEL_LENGTH = 200;

    private final int maxNodes;
    private final int maxEdges;
    private final int maxConfigBytesPerNode;

    public SchemaValidator(
            @Value("${graph-compiler.validation.max-nodes:500}") int maxNodes,
            @Value("${graph-compiler.validation.max-edges:2000}") int maxEdges,
            @Value("${graph-compiler.validation.max-config-bytes-per-node:" + DEFAULT_MAX_CONFIG_BYTES_PER_NODE + "}")
            int maxConfigBytesPerNode) {
        this.maxNodes = maxNodes > 0 ? maxNodes : 500;
        this.maxEdges = maxEdges > 0 ? maxEdges : 2000;
        this.maxConfigBytesPerNode = maxConfigBytesPerNode > 0 ? maxConfigBytesPerNode : DEFAULT_MAX_CONFIG_BYTES_PER_NODE;
    }

    public ValidationResult validate(Topology topology) {
        ValidationResult result = new ValidationResult();

        if (topology == null) {
            result.addError("topology", "NULL_TOPOLOGY", "Topology cannot be null", ValidationError.Severity.ERROR);
            return result;
        }

        List<CanvasNode> nodes = topology.getNodes();
        if (nodes.isEmpty()) {
            result.addError("nodes", "EMPTY_TOPOLOGY",
                    "Topology must contain at least 1 node", ValidationError.Severity.ERROR);
            return result;
        }

        if (nodes.size() > maxNodes) {
            result.addError("nodes", "TOO_MANY_NODES",
                    "Topology exceeds maximum of " + maxNodes + " nodes (found " + nodes.size() + "). Remove "
                            + (nodes.size() - maxNodes) + " nodes or raise the limit.",
                    ValidationError.Severity.ERROR);
        }

        int edgeCount = topology.getEdges().size();
        if (edgeCount > maxEdges) {
            result.addError("edges", "TOO_MANY_EDGES",
                    "Topology exceeds maximum of " + maxEdges + " edges (found " + edgeCount + "). Remove "
                            + (edgeCount - maxEdges) + " edges or raise the limit.",
                    ValidationError.Severity.ERROR);
        }

        Set<String> nodeIds = new HashSet<>();

        for (int i = 0; i < nodes.size(); i++) {
            CanvasNode node = nodes.get(i);
            String fieldPrefix = "nodes[" + i + "]";

            if (node == null) {
                result.addError(fieldPrefix, "NULL_NODE", "Node at index " + i + " is null",
                        ValidationError.Severity.ERROR);
                continue;
            }

            String id = node.getId();

            if (id == null || id.isBlank()) {
                result.addError(fieldPrefix + ".id", "MISSING_NODE_ID",
                        "Node at index " + i + " is missing an ID. Every node must have a unique identifier.",
                        ValidationError.Severity.ERROR);
            } else if (id.length() > MAX_ID_LENGTH) {
                result.addError(fieldPrefix + ".id", "NODE_ID_TOO_LONG",
                        "Node ID exceeds " + MAX_ID_LENGTH + " characters. Node IDs appear in log lines, "
                                + "metric labels and Kafka message keys, so they must stay short.",
                        ValidationError.Severity.ERROR);
            } else if (!nodeIds.add(id)) {
                result.addError(fieldPrefix + ".id", "DUPLICATE_NODE_ID",
                        "Duplicate node ID: '" + id + "'. Node IDs must be unique.", ValidationError.Severity.ERROR);
            }

            if (node.getType() == null || node.getType().isBlank()) {
                result.addError(fieldPrefix + ".type", "MISSING_NODE_TYPE",
                        "Node '" + id + "' is missing a type. Set a type for this node.",
                        ValidationError.Severity.ERROR);
            }

            if (node.getData() == null) {
                result.addError(fieldPrefix + ".data", "MISSING_NODE_DATA",
                        "Node '" + id + "' is missing data. Add data to this node.",
                        ValidationError.Severity.ERROR);
            } else {
                String label = node.getData().getLabel();
                if (label == null || label.isBlank()) {
                    result.addError(fieldPrefix + ".data.label", "MISSING_NODE_LABEL",
                            "Node '" + id + "' is missing a label. Add a label to identify this node.",
                            ValidationError.Severity.WARNING);
                } else if (label.length() > MAX_LABEL_LENGTH) {
                    result.addError(fieldPrefix + ".data.label", "NODE_LABEL_TOO_LONG",
                            "Node '" + id + "' label exceeds " + MAX_LABEL_LENGTH + " characters.",
                            ValidationError.Severity.ERROR);
                }

                validateConfigBudget(node, fieldPrefix, result);
            }

            if (node.getPosition() == null) {
                result.addError(fieldPrefix + ".position", "MISSING_NODE_POSITION",
                        "Node '" + id + "' is missing a position. This may cause rendering issues.",
                        ValidationError.Severity.WARNING);
            }

            // parentId may legitimately point at a node declared later, so this is
            // checked against the full node list rather than nodeIds accumulated so far.
            if (node.getParentId() != null && !node.getParentId().equals(id)) {
                boolean parentExists = nodes.stream()
                        .anyMatch(n -> n != null && node.getParentId().equals(n.getId()));
                if (!parentExists) {
                    result.addError(fieldPrefix + ".parentId", "INVALID_PARENT_ID",
                            "Node '" + id + "' references parent '" + node.getParentId()
                                    + "' which does not exist in the topology.",
                            ValidationError.Severity.ERROR);
                }
            }
        }

        return result;
    }

    /**
     * Bounds {@code node.data.config}.
     *
     * <p>Approximate by entry count rather than serialising: the point is to reject a
     * pathological payload early, and a JSON round-trip per node on the validation
     * path would cost more than the check is worth. Node and edge caps bound the node
     * count, so entry count bounds the total payload.
     */
    private void validateConfigBudget(CanvasNode node, String fieldPrefix, ValidationResult result) {
        var config = node.getData() == null ? null : node.getData().getConfig();
        if (config == null || config.isEmpty()) {
            return;
        }

        int entries = config.size();
        // A generous per-entry allowance: even at 128 bytes each, 32 entries stays
        // comfortably inside the budget.
        int maxEntries = Math.max(4, maxConfigBytesPerNode / 128);

        if (entries > maxEntries) {
            result.addError(fieldPrefix + ".data.config", "CONFIG_TOO_LARGE",
                    "Node '" + node.getId() + "' has " + entries + " config entries (limit " + maxEntries + "). "
                            + "Node configuration is compiled into the simulation task and must stay bounded.",
                    ValidationError.Severity.ERROR);
        }

        long scalarChars = config.values().stream()
                .filter(java.util.Objects::nonNull)
                .mapToLong(v -> v.toString().length())
                .sum();

        if (scalarChars > maxConfigBytesPerNode) {
            result.addError(fieldPrefix + ".data.config", "CONFIG_VALUE_TOO_LARGE",
                    "Node '" + node.getId() + "' config values total " + scalarChars
                            + " characters (limit " + maxConfigBytesPerNode + ").",
                    ValidationError.Severity.ERROR);
        }
    }
}
