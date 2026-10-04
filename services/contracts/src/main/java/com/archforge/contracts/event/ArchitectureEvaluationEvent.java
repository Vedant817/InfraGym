package com.archforge.contracts.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * A structured architectural evaluation. Produced by the AI evaluator on
 * {@code evaluation.results}, consumed by the design service for persistence and
 * the gateway for push delivery.
 *
 * <p>Every field here is typed. The previous implementation asked the model for
 * {@code SCORE: 82\nSTRENGTHS: a, b, c} and regex-parsed the reply, which silently
 * produced score 50 and empty lists whenever the model phrased anything
 * differently. The model is now constrained to emit JSON that maps onto these
 * types, so a malformed response fails loudly at the boundary instead of
 * degrading into plausible-looking fiction.
 *
 * <p>{@code degraded} is the escape hatch for when the LLM is unavailable. A
 * deterministic, metrics-only critique is still published — flagged as degraded
 * — because a user with a slow P99 deserves an answer now, not an error page.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArchitectureEvaluationEvent(
        String eventId,
        String correlationId,
        String evaluationId,
        String simulationId,
        String submissionId,
        int overallScore,
        String verdict,
        String summary,
        List<Bottleneck> bottlenecks,
        List<Finding> findings,
        List<String> strengths,
        List<String> weaknesses,
        List<String> recommendations,
        List<KnowledgeReference> references,
        String model,
        long latencyMs,
        int promptTokens,
        int completionTokens,
        boolean degraded,
        String degradedReason,
        Instant evaluatedAt) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW, INFO
    }

    /** The component limiting the architecture, with the measurement that proves it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bottleneck(
            String nodeId,
            String nodeType,
            String metric,
            double observedValue,
            double thresholdValue,
            String unit,
            String explanation) {
    }

    /** A single piece of feedback, always tied to evidence. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Finding(
            String id,
            Severity severity,
            String category,
            String title,
            String detail,
            String evidence,
            String recommendation,
            List<String> relatedNodeIds) {

        public Finding {
            if (severity == null) {
                severity = Severity.MEDIUM;
            }
            if (relatedNodeIds == null) {
                relatedNodeIds = List.of();
            }
        }
    }

    /** A retrieved knowledge-base document, recorded so the verdict is auditable. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KnowledgeReference(
            String id,
            String kind,
            String title,
            double similarity,
            String snippet) {
    }

    public ArchitectureEvaluationEvent {
        if (overallScore < 0) {
            overallScore = 0;
        }
        if (overallScore > 100) {
            overallScore = 100;
        }
        if (bottlenecks == null) {
            bottlenecks = List.of();
        }
        if (findings == null) {
            findings = List.of();
        }
        if (strengths == null) {
            strengths = List.of();
        }
        if (weaknesses == null) {
            weaknesses = List.of();
        }
        if (recommendations == null) {
            recommendations = List.of();
        }
        if (references == null) {
            references = List.of();
        }
        if (evaluatedAt == null) {
            evaluatedAt = Instant.now();
        }
    }

    public List<Finding> findingsAtLeast(Severity threshold) {
        return findings.stream()
                .filter(f -> f.severity() != null && f.severity().ordinal() <= threshold.ordinal())
                .toList();
    }

    public static String[] allSeverityNames() {
        return Arrays.stream(Severity.values()).map(Enum::name).toArray(String[]::new);
    }
}
