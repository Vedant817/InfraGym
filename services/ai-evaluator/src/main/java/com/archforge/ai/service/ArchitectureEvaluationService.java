package com.archforge.ai.service;

import com.archforge.ai.analysis.ArchitectureAnalysis;
import com.archforge.ai.config.EvaluatorProperties;
import com.archforge.ai.event.EvaluationLifecyclePublisher;
import com.archforge.contracts.event.ArchitectureEvaluationEvent;
import com.archforge.contracts.event.SimulationSummaryEvent;
import com.archforge.messaging.KafkaTopics;
import com.archforge.contracts.event.EvaluationLifecycleEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Turns a finished run into an evaluation.
 *
 * <p><b>The order of operations is the design.</b> The arithmetic runs first and is always
 * published. The model runs second, on a bounded budget, and can only <em>add</em> -- it can
 * contribute prose and findings the measurements did not already yield, and it can never
 * change the score, the verdict band, the bottlenecks, or anything the summary itself says.
 * If it fails, times out, or is not configured, the event is still published with
 * {@code degraded = true} and a reason.
 *
 * <p>That ordering is the difference between an evaluation and a paragraph. The previous
 * implementation had no deterministic half at all: the score was whatever string the model
 * emitted after {@code SCORE:} was found in a free-text response, and a response phrased
 * any other way produced a silent 50 with empty lists. There was nothing to degrade to.
 */
@Slf4j
@Service
public class ArchitectureEvaluationService {

    /** How long a publish may take before it is treated as failed. */
    static final int AWAIT_TIMEOUT_SECONDS = 15;

    /**
     * Runs the model off the listener thread.
     *
     * <p>The listener thread is shared by every evaluation this instance is handling. A model
     * that hangs would stop consuming every other run's summary, and the symptom would be a
     * batch of submissions quietly stuck at "evaluating".
     */
    private final ExecutorService modelExecutor;

    private final DeterministicAnalysisRunner analysisRunner;
    private final NarrativeModel narrativeModel;
    private final KnowledgeRetriever knowledgeRetriever;
    private final EvaluationLifecyclePublisher lifecyclePublisher;
    private final EvaluationResultPublisher resultPublisher;
    private final EvaluatorProperties properties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ArchitectureEvaluationService(DeterministicAnalysisRunner analysisRunner,
                                        NarrativeModel narrativeModel,
                                        KnowledgeRetriever knowledgeRetriever,
                                        EvaluationLifecyclePublisher lifecyclePublisher,
                                        EvaluationResultPublisher resultPublisher,
                                        EvaluatorProperties properties,
                                        ObjectMapper objectMapper,
                                        MeterRegistry meterRegistry,
                                        @Qualifier("evaluationModelExecutor")
                                        ExecutorService modelExecutor) {
        this.analysisRunner = analysisRunner;
        this.narrativeModel = narrativeModel;
        this.knowledgeRetriever = knowledgeRetriever;
        this.lifecyclePublisher = lifecyclePublisher;
        this.resultPublisher = resultPublisher;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.modelExecutor = modelExecutor;
    }

    /**
     * Evaluates one run and publishes the result.
     *
     * @return the published event, which is also what the caller should return rather than a
     *         partially-built intermediate
     */
    public ArchitectureEvaluationEvent evaluate(SimulationSummaryEvent summary) {
        String evaluationId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        lifecyclePublisher.started(summary, evaluationId);

        // Step 1: the arithmetic. Never fails on anything a model can fail on.
        ArchitectureAnalysis analysis = analysisRunner.analyse(summary);
        meterRegistry.counter("evaluator.analysis.score", "verdict", analysis.verdict())
                .increment(analysis.overallScore());

        // Step 2: references. Best-effort by construction, and its own shortfall is folded
        // into the caveats so "no references" and "could not reach references" stay
        // distinguishable on the published record.
        KnowledgeRetriever.Retrieval retrieval = knowledgeRetriever.retrieve(
                summary, properties.getRag().getTopK());
        if (retrieval.degraded()) {
            analysis = analysis.withCaveat(retrieval.note());
        }
        List<ArchitectureEvaluationEvent.KnowledgeReference> references = retrieval.references();

        // Step 3: the narrative, on a budget, contributing at most.
        NarrativeModel.Narrative narrative = narrativeModel(properties.isModelEnabled()
                ? summary : null, analysis, references, startedAt);

        ArchitectureEvaluationEvent event = merge(summary, analysis, narrative, references,
                evaluationId, System.nanoTime() - startedAt);

        resultPublisher.publish(event);
        lifecyclePublisher.completed(summary, evaluationId, event.degraded(),
                event.degradedReason());

        log.info("Evaluated simulation {} (submission {}): score {}/100, verdict {}, "
                        + "{} finding(s), {} bottleneck(s), {} reference(s), model {}, {} ms{}",
                summary.simulationId(), summary.submissionId(), event.overallScore(),
                event.verdict(), event.findings().size(), event.bottlenecks().size(),
                references.size(), event.model(), event.latencyMs(),
                event.degraded() ? " (degraded: " + event.degradedReason() + ")" : "");
        return event;
    }

    /**
     * Asks the model for its contribution, or explains why there isn't one.
     *
     * <p>The timeout is the important part. The deterministic evaluation is already
     * computed and waiting; the prose is a bonus on top of it. Making a user wait minutes
     * for the bonus is strictly worse than publishing the measurements now and the commentary
     * never -- so the budget is enforced, not merely hoped for.
     */
    private NarrativeModel.Narrative narrativeModel(SimulationSummaryEvent summary,
                                                   ArchitectureAnalysis analysis,
                                                   List<ArchitectureEvaluationEvent.KnowledgeReference> references,
                                                   long startedAtNanos) {
        if (!properties.isModelEnabled()) {
            meterRegistry.counter("evaluator.model.disabled").increment();
            return NarrativeModel.Narrative.unavailable(
                    "No narrative was generated: the model is disabled by configuration. The "
                            + "score, verdict and findings below are the complete arithmetic evaluation.");
        }
        if (!narrativeModel.available()) {
            meterRegistry.counter("evaluator.model.unavailable").increment();
            return NarrativeModel.Narrative.unavailable(
                    "No narrative was generated: no model is configured or reachable. The score, "
                            + "verdict and findings below are the complete arithmetic evaluation.");
        }

        CompletableFuture<NarrativeModel.Narrative> future =
                CompletableFuture.supplyAsync(
                        () -> narrativeModel.narrate(summary, analysis, references), modelExecutor);
        try {
            NarrativeModel.Narrative narrative = future.get(
                    properties.getModelTimeoutMillis(), TimeUnit.MILLISECONDS);
            meterRegistry.counter("evaluator.model.succeeded").increment();
            return narrative;
        } catch (TimeoutException e) {
            future.cancel(true);
            meterRegistry.counter("evaluator.model.timeout").increment();
            log.warn("Model did not respond within {} ms for simulation {}; publishing the "
                            + "deterministic evaluation without it",
                    properties.getModelTimeoutMillis(), analysis.overallScore() >= 0
                            ? "under evaluation" : "unknown");
            return NarrativeModel.Narrative.unavailable(String.format(Locale.ROOT,
                    "No narrative was generated: the model did not respond within %d ms. The "
                            + "score, verdict and findings below are the complete arithmetic "
                            + "evaluation and are unaffected.",
                    properties.getModelTimeoutMillis()));
        } catch (ExecutionException e) {
            meterRegistry.counter("evaluator.model.failed").increment();
            log.warn("Model call failed: {}", e.getCause() == null ? e.getMessage()
                    : e.getCause().getMessage());
            return NarrativeModel.Narrative.unavailable(
                    "No narrative was generated: the model call failed (" + e.getMessage()
                            + "). The score, verdict and findings below are the complete "
                            + "arithmetic evaluation and are unaffected.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            meterRegistry.counter("evaluator.model.interrupted").increment();
            return NarrativeModel.Narrative.unavailable(
                    "No narrative was generated: the evaluation was interrupted.");
        }
    }

    /**
     * Combines the two halves.
     *
     * <p>The merge is one-directional by construction: the arithmetic's score, verdict,
     * bottlenecks and findings are placed first and unconditionally, and the model's
     * contribution is appended. There is no code path on which the model can alter a
     * measurement-derived value, so "the model changed the score" is not a bug that can be
     * introduced later without being noticed here.
     */
    private ArchitectureEvaluationEvent merge(SimulationSummaryEvent summary,
                                              ArchitectureAnalysis analysis,
                                              NarrativeModel.Narrative narrative,
                                              List<ArchitectureEvaluationEvent.KnowledgeReference> references,
                                              String evaluationId,
                                              long elapsedNanos) {
        List<ArchitectureEvaluationEvent.Finding> findings = new ArrayList<>(analysis.findings());
        List<ArchitectureEvaluationEvent.Finding> rejected = new ArrayList<>();
        for (ArchitectureEvaluationEvent.Finding finding : narrative.additionalFindings()) {
            if (finding == null || finding.title() == null || finding.title().isBlank()) {
                rejected.add(finding);
                continue;
            }
            findings.add(finding);
        }
        boolean degraded = narrative.model() == null || "none".equals(narrative.model());

        List<String> summaryText = new ArrayList<>();
        if (!narrative.summary().isBlank()) {
            summaryText.add(narrative.summary());
        }
        if (!narrative.verdictRationale().isBlank()) {
            summaryText.add(narrative.verdictRationale());
        }
        if (summaryText.isEmpty()) {
            // Never publish an empty summary. A reader who gets nothing is worse off than one
            // who gets the arithmetic restated, and an empty string on the page reads as a bug.
            summaryText.add(String.format(Locale.ROOT,
                    "Scored %d/100 (%s) across %d component(s) over %d tick(s) under the %s "
                            + "scenario. %d finding(s) breached a measured threshold and %d "
                            + "measurement(s) argued for the design.",
                    analysis.overallScore(), analysis.verdict(), summary.nodeSummaries().size(),
                    summary.tickCount(), summary.scenario(), analysis.findings().size(),
                    analysis.strengths().size()));
        }

        return new ArchitectureEvaluationEvent(
                UUID.randomUUID().toString(),
                summary.correlationId(),
                evaluationId,
                summary.simulationId(),
                summary.submissionId(),
                analysis.overallScore(),
                analysis.verdict(),
                String.join(" ", summaryText),
                analysis.bottlenecks(),
                findings,
                analysis.strengths(),
                analysis.weaknesses(),
                analysis.recommendations(),
                references,
                narrative.model(),
                elapsedNanos / 1_000_000,
                narrative.promptTokens(),
                narrative.completionTokens(),
                degraded,
                degraded ? narrative.summary() : null,
                java.time.Instant.now());
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }
}