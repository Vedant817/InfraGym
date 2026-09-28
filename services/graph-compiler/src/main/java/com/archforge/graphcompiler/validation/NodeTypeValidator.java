package com.archforge.graphcompiler.validation;

import com.archforge.graphcompiler.schema.ReactFlowNode;
import com.archforge.graphcompiler.schema.ReactFlowTopology;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class NodeTypeValidator {

    private static final int MAX_TYPE_LENGTH = 100;

    public ValidationResult validate(ReactFlowTopology topology) {
        ValidationResult result = new ValidationResult();

        if (topology == null || topology.getNodes() == null) {
            return result;
        }

        List<ReactFlowNode> nodes = topology.getNodes();
        Set<String> validTypes = com.archforge.graphcompiler.schema.NodeType.getAllValues();

        for (int i = 0; i < nodes.size(); i++) {
            ReactFlowNode node = nodes.get(i);
            if (node == null || node.getId() == null) {
                continue;
            }

            String fieldPrefix = "nodes[" + i + "]";
            String type = node.getType();

            if (type == null || type.isBlank()) {
                continue;
            }

            if (type.length() > MAX_TYPE_LENGTH) {
                result.addError(fieldPrefix + ".type", "INVALID_NODE_TYPE",
                        "Node '" + node.getId() + "' has type exceeding maximum length of " + MAX_TYPE_LENGTH + " characters",
                        ValidationError.Severity.ERROR);
                continue;
            }

            if (!isValidType(type)) {
                String suggestion = findClosestMatch(type);
                String message = "Node '" + node.getId() + "' has unknown type '" + type + "'.";
                if (suggestion != null) {
                    message += " Did you mean '" + suggestion + "'?";
                }
                message += " Valid types: " + String.join(", ", validTypes);

                result.addError(fieldPrefix + ".type", "INVALID_NODE_TYPE", message, ValidationError.Severity.ERROR);
            }
        }

        return result;
    }

    private boolean isValidType(String type) {
        return com.archforge.graphcompiler.schema.NodeType.getAllValues().contains(type.toLowerCase());
    }

    private String findClosestMatch(String input) {
        if (input.length() > MAX_TYPE_LENGTH) {
            return null;
        }

        String closest = null;
        int minDistance = Integer.MAX_VALUE;

        for (String validType : com.archforge.graphcompiler.schema.NodeType.getAllValues()) {
            int distance = levenshteinDistance(input.toLowerCase(), validType);
            if (distance < minDistance && distance <= 3) {
                minDistance = distance;
                closest = validType;
            }
        }

        return closest;
    }

    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= s2.length(); j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }

        return dp[s1.length()][s2.length()];
    }
}
