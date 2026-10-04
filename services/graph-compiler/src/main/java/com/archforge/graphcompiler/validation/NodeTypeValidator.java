package com.archforge.graphcompiler.validation;

import com.archforge.contracts.canvas.CanvasNode;
import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.canvas.Topology;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates node types and, where possible, suggests the intended one.
 *
 * <p><b>DoS protection.</b> The edit-distance suggestion allocates an
 * {@code (m+1)(n+1)} two-dimensional array. For a 100 000-character type string
 * against a 10-character candidate that is ~10<sup>7</sup> entries — gigabytes of
 * heap, from one HTTP request body. The previous version added a 100-character guard
 * to the <em>suggestion</em> path but the type was still interpolated into an error
 * message, so a large body was still accepted and buffered.
 *
 * <p>Fixes applied:
 * <ul>
 *   <li>The type string is length-checked and rejected before any allocation, and the
 *       offending value is truncated in the message so the response stays bounded.</li>
 *   <li>Edit distance uses a two-row rolling array, O(n) space instead of
 *       O(m·n).</li>
 *   <li>Ties in "did you mean" resolve by declaration order rather than hash order, so
 *       the suggestion is stable across JVM restarts.</li>
 * </ul>
 */
@Component
public class NodeTypeValidator {

    /** Longest type string accepted before the suggestion machinery is skipped entirely. */
    static final int MAX_TYPE_LENGTH = 100;

    /** Longest prefix of an offending value echoed back in an error message. */
    private static final int MAX_ECHOED_VALUE = 40;

    /** Longest distance still considered a plausible typo. */
    private static final int MAX_SUGGESTION_DISTANCE = 3;

    public ValidationResult validate(Topology topology) {
        ValidationResult result = new ValidationResult();

        if (topology == null) {
            return result;
        }

        List<CanvasNode> nodes = topology.getNodes();

        for (int i = 0; i < nodes.size(); i++) {
            CanvasNode node = nodes.get(i);
            if (node == null || node.getId() == null) {
                continue;
            }

            String fieldPrefix = "nodes[" + i + "]";
            String type = node.getType();

            if (type == null || type.isBlank()) {
                continue; // SchemaValidator reports the missing type
            }

            if (type.length() > MAX_TYPE_LENGTH) {
                result.addError(fieldPrefix + ".type", "INVALID_NODE_TYPE",
                        "Node '" + node.getId() + "' has a type of " + type.length()
                                + " characters, exceeding the maximum of " + MAX_TYPE_LENGTH
                                + ". Valid types: " + String.join(", ", NodeType.getAllValues()),
                        ValidationError.Severity.ERROR);
                continue;
            }

            if (NodeType.findByValue(type) != null) {
                continue;
            }

            String message = "Node '" + node.getId() + "' has unknown type '" + truncate(type) + "'.";
            String suggestion = findClosestMatch(type);
            if (suggestion != null) {
                message += " Did you mean '" + suggestion + "'?";
            }
            message += " Valid types: " + String.join(", ", NodeType.getAllValues());

            result.addError(fieldPrefix + ".type", "INVALID_NODE_TYPE", message, ValidationError.Severity.ERROR);
        }

        return result;
    }

    /**
     * Finds the nearest valid type, preferring declaration order on a tie.
     *
     * <p>{@code NodeType.getAllValues()} returns a {@link java.util.LinkedHashSet}, so
     * iteration order is the enum's declaration order — matching a distance keeps the
     * first such type, which is stable.
     */
    private String findClosestMatch(String input) {
        if (input.length() > MAX_TYPE_LENGTH) {
            return null;
        }

        String closest = null;
        int minDistance = Integer.MAX_VALUE;

        for (String validType : NodeType.getAllValues()) {
            int distance = levenshteinDistance(input.toLowerCase(java.util.Locale.ROOT), validType);
            if (distance < minDistance && distance <= MAX_SUGGESTION_DISTANCE) {
                minDistance = distance;
                closest = validType;
            }
        }

        return closest;
    }

    /**
     * Levenshtein distance using two rolling rows.
     *
     * <p>{@code O(min(m,n))} space rather than the {@code O(m·n)} matrix the previous
     * version allocated, and {@code O(m·n)} time. Both inputs are bounded by
     * {@link #MAX_TYPE_LENGTH}, so the worst case is trivial.
     */
    private int levenshteinDistance(String s1, String s2) {
        int n = s2.length();
        if (n == 0) {
            return s1.length();
        }

        int[] previous = new int[n + 1];
        int[] current = new int[n + 1];

        for (int j = 0; j <= n; j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= s1.length(); i++) {
            current[0] = i;
            char c1 = s1.charAt(i - 1);

            for (int j = 1; j <= n; j++) {
                int cost = c1 == s2.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(previous[j] + 1, current[j - 1] + 1),
                        previous[j - 1] + cost);
            }

            int[] swap = previous;
            previous = current;
            current = swap;
        }

        return previous[n];
    }

    private String truncate(String value) {
        return value.length() <= MAX_ECHOED_VALUE
                ? value
                : value.substring(0, MAX_ECHOED_VALUE) + "… (" + value.length() + " chars)";
    }
}
