package com.archforge.ai.analysis;

import com.archforge.contracts.event.ArchitectureEvaluationEvent;

/**
 * What can be concluded from a run's measurements alone, with no model involved.
 *
 * <p><b>Why this exists.</b> The whole premise of the product is that an architectural
 * assessment is grounded in what the simulation measured rather than in what a language
 * model finds plausible. An LLM-only evaluation is a confident paragraph with no
 * arithmetic behind it: asked about a design whose database had a P99 of 850 ms, a model
 * will happily write about caching strategies without ever having been told the number.
 *
 * <p>So the numbers are worked out first, here, and the model is asked to explain them
 * rather than to discover them. Three consequences, all of them the point:
 *
 * <ul>
 *   <li>{@link #overallScore()} is arithmetic. Not a vibe, not the model's opinion of a
 *       number it was handed. A reader can recompute it from the summary.</li>
 *   <li>Every {@link #bottlenecks()} entry carries the observation and the threshold it
 *       breached, so "the database is your bottleneck" is a checkable claim.</li>
 *   <li>It works with the model switched off. That is what {@code degraded} on the
 *       published event means: a user with a 850 ms P99 deserves an answer now, not an
 *       error page and not a spinner.</li>
 * </ul>
 *
 * @param overallScore 0-100, computed from the penalties below
 * @param verdict      one of {@link Verdict}'s labels
 * @param bottlenecks  the components limiting the run, most severe first
 * @param findings     every observation, each tied to its evidence
 * @param strengths    measured facts that argue <em>for</em> the design
 * @param weaknesses   measured facts that argue against it, as findings
 * @param recommendations what to change, each one traceable to a finding
 */
public record ArchitectureAnalysis(
        int overallScore,
        String verdict,
        java.util.List<ArchitectureEvaluationEvent.Bottleneck> bottlenecks,
        java.util.List<ArchitectureEvaluationEvent.Finding> findings,
        java.util.List<String> strengths,
        java.util.List<String> weaknesses,
        java.util.List<String> recommendations,
        java.util.List<String> caveats) {

    public ArchitectureAnalysis {
        bottlenecks = bottlenecks == null ? java.util.List.of() : java.util.List.copyOf(bottlenecks);
        findings = findings == null ? java.util.List.of() : java.util.List.copyOf(findings);
        strengths = strengths == null ? java.util.List.of() : java.util.List.copyOf(strengths);
        weaknesses = weaknesses == null ? java.util.List.of() : java.util.List.copyOf(weaknesses);
        recommendations = recommendations == null ? java.util.List.of()
                : java.util.List.copyOf(recommendations);
        caveats = caveats == null ? java.util.List.of() : java.util.List.copyOf(caveats);
    }

    /**
     * A copy carrying one more caveat.
     *
     * <p>Records are immutable and caveats are discovered at different stages -- the
     * arithmetic finds its own, retrieval finds its own -- so they are combined by copying
     * rather than by mutation. Both end up on the same published event, which is the only
     * place a reader will ever see them.
     */
    public ArchitectureAnalysis withCaveat(String caveat) {
        if (caveat == null || caveat.isBlank()) {
            return this;
        }
        java.util.List<String> combined = new java.util.ArrayList<>(caveats);
        combined.add(caveat);
        return new ArchitectureAnalysis(overallScore, verdict, bottlenecks, findings, strengths,
                weaknesses, recommendations, combined);
    }

    /**
     * Score bands.
     *
     * <p>The boundaries are not arbitrary decoration; each one marks a change in what the
     * measurements mean. Below {@link #AT_RISK} a run produced requests that never completed,
     * which is a different category of answer from "slow".
     */
    public enum Verdict {
        /** Within every threshold, nothing saturated, nothing queued at the end. */
        STRONG("strong"),
        /** Comfortably inside thresholds, with real headroom left. */
        SOUND("sound"),
        /** Meets the thresholds but with little margin, or a clear single point of failure. */
        NEEDS_WORK("needs work"),
        /** Breached a threshold: the design works and does not scale. */
        AT_RISK("at risk"),
        /** Requests were rejected or never completed. Not a tuning problem. */
        NOT_VIABLE("not viable under this scenario");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Verdict forScore(int score, boolean backlogged) {
            if (backlogged) {
                return NOT_VIABLE;
            }
            if (score >= 85) {
                return STRONG;
            }
            return score >= 70 ? SOUND : score >= 50 ? NEEDS_WORK : AT_RISK;
        }
    }

    /**
     * A single observation, before it becomes a wire event.
     *
     * <p>Kept as its own type so the scoring can accumulate a penalty and an explanation
     * together. Splitting them is how you end up with a score nobody can reproduce: a
     * penalty applied in one place and explained in another drifts within a week.
     *
     * @param penalty points deducted from 100; zero for a strength
     */
    public record Observation(
            String nodeId,
            String nodeType,
            String metric,
            double observedValue,
            double thresholdValue,
            String unit,
            String explanation,
            int penalty,
            ArchitectureEvaluationEvent.Severity severity,
            String recommendation,
            boolean strength) {

        public Observation {
            if (nodeId == null) {
                nodeId = "architecture";
            }
            if (severity == null) {
                severity = ArchitectureEvaluationEvent.Severity.MEDIUM;
            }
        }

        /** The bottleneck record a reader can check against the summary. */
        public ArchitectureEvaluationEvent.Bottleneck toBottleneck() {
            return new ArchitectureEvaluationEvent.Bottleneck(nodeId, nodeType, metric,
                    round(observedValue), thresholdValue, unit, explanation);
        }
    }

    static double round(double value) {
        // Two decimals is the resolution the summary itself carries; more digits would
        // imply precision the measurement does not have.
        return Math.round(value * 100.0) / 100.0;
    }
}