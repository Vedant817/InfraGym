package com.archforge.ai.service;

import com.archforge.ai.analysis.ArchitectureAnalysis;
import com.archforge.contracts.event.ArchitectureEvaluationEvent;
import com.archforge.contracts.event.SimulationSummaryEvent;

import java.util.List;

/**
 * The model's contribution to an evaluation.
 *
 * <p>An interface rather than a direct {@code ChatClient} dependency, for one reason: the
 * deterministic half of an evaluation is the half the reader is asked to trust, and it has to
 * be testable, and demonstrable, and demonstrable *without* a model. Keeping the model
 * behind a seam means every degraded-path behaviour -- no key configured, provider down,
 * request timed out, response unparseable -- is a unit test rather than something found in
 * production.
 */
public interface NarrativeModel {

    /**
     * What the model said.
     *
     * @param summary the run
     * @param analysis what the arithmetic already established, which the model is shown and
     *                 must not contradict
     * @param references retrieved knowledge-base documents, possibly empty
     */
    Narrative narrate(SimulationSummaryEvent summary,
                      ArchitectureAnalysis analysis,
                      List<ArchitectureEvaluationEvent.KnowledgeReference> references);

    /** The model identifier, recorded on the published event so a verdict is attributable. */
    String modelName();

    /** Whether this implementation can be used at all right now. */
    boolean available();

    /**
     * The model's narrative contribution, already validated.
     *
     * <p>Never null. Every field is defaulted rather than optional, because the alternative
     * is a null check at every consumer.
     *
     * @param additionalFindings findings the model raised that the arithmetic did not, and
     *                           which must each carry a measurement as evidence
     */
    record Narrative(
            String summary,
            String verdictRationale,
            List<ArchitectureEvaluationEvent.Finding> additionalFindings,
            String questionForUser,
            long latencyMs,
            int promptTokens,
            int completionTokens,
            String model) {

        public Narrative {
            summary = summary == null || summary.isBlank()
                    ? "" : summary.trim();
            verdictRationale = verdictRationale == null ? "" : verdictRationale.trim();
            additionalFindings = additionalFindings == null
                    ? List.of() : List.copyOf(additionalFindings);
            questionForUser = questionForUser == null || questionForUser.isBlank()
                    ? null : questionForUser.trim();
            model = model == null || model.isBlank() ? "unknown" : model;
        }

        public static Narrative unavailable(String reason) {
            return new Narrative(reason, "", List.of(), null, 0, 0, 0, "none");
        }
    }
}