package com.archforge.design.domain;

import com.archforge.contracts.event.ArchitectureEvaluationEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One evaluation's verdict, plus the findings that justify it.
 *
 * <p>{@code degraded} is a column, not something a reader has to infer. A score of 56 with a
 * model's narrative and a score of 56 from arithmetic alone are different things, and a
 * reader shown only the number cannot tell them apart.
 */
public record Evaluation(
        UUID id,
        UUID submissionId,
        UUID simulationId,
        int overallScore,
        String verdict,
        String summary,
        List<String> strengths,
        List<String> weaknesses,
        List<String> recommendations,
        List<ArchitectureEvaluationEvent.Bottleneck> bottlenecks,
        List<Finding> findings,
        List<com.archforge.contracts.event.ArchitectureEvaluationEvent.KnowledgeReference> references,
        List<String> warnings,
        String model,
        long latencyMs,
        int promptTokens,
        int completionTokens,
        boolean degraded,
        String degradedReason,
        Instant evaluatedAt) {

    /** One finding, with its position so redelivery replaces rather than accumulates. */
    public record Finding(
            int position,
            String nodeId,
            ArchitectureEvaluationEvent.Severity severity,
            String category,
            String title,
            String detail,
            String evidence,
            String recommendation,
            List<String> relatedNodeIds) {
    }

    /**
     * The findings at or above a severity, worst first.
     *
     * <p>Ordered rather than filtered in the caller's order: every consumer of this list --
     * the REST endpoint, the frontend, a future notification -- wants the same ordering, and
     * an unordered list would let each of them invent its own.
     */
    public List<Finding> findingsAtLeast(ArchitectureEvaluationEvent.Severity threshold) {
        return findings.stream()
                .filter(finding -> finding.severity() != null
                        && finding.severity().ordinal() <= threshold.ordinal())
                .sorted(java.util.Comparator.comparingInt(
                        finding -> finding.severity().ordinal()))
                .toList();
    }

    /** The single most serious thing to say about this design, or null if nothing is wrong. */
    public Finding worstFinding() {
        return findings.stream()
                .min(java.util.Comparator.comparingInt(
                        finding -> finding.severity() == null
                                ? ArchitectureEvaluationEvent.Severity.MEDIUM.ordinal()
                                : finding.severity().ordinal()))
                .orElse(null);
    }
}