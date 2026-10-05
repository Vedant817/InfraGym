package com.archforge.design.ingest;

import com.archforge.contracts.event.ArchitectureEvaluationEvent;
import com.archforge.contracts.event.EvaluationLifecycleEvent;
import com.archforge.design.domain.Evaluation;
import com.archforge.design.domain.Evaluation.Finding;
import com.archforge.design.domain.SubmissionEventEntity;
import com.archforge.design.repository.EvaluationRepository;
import com.archforge.design.repository.SubmissionEventRepository;
import com.archforge.design.repository.SubmissionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Records the verdict, and keeps the event log complete across both evaluation topics.
 *
 * <p>{@code evaluation.results} carries the assessment and {@code evaluation.lifecycle}
 * carries the transitions around it, and both are recorded. That is not duplication: the
 * lifecycle topic says a user watching a run saw "evaluating" and then "completed", and it
 * is the only thing that can explain a gap in which a run appears to stall. Folding it into
 * the submission's own event log means one ordered history of a submission rather than two
 * timelines the reader has to correlate by hand.
 *
 * <p>As with the simulation summary, the warnings are persisted rather than logged. An
 * evaluation whose trace was partial carries that fact in its {@code degradedReason}; storing
 * the score without it would produce a number that looks exactly as reliable as one the
 * arithmetic fully supported.
 */
@Slf4j
@Component
public class EvaluationListener {

    private final EvaluationRepository evaluations;
    private final SubmissionRepository submissions;
    private final SubmissionEventRepository events;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public EvaluationListener(EvaluationRepository evaluations,
                              SubmissionRepository submissions,
                              SubmissionEventRepository events,
                              ObjectMapper objectMapper,
                              MeterRegistry meterRegistry) {
        this.evaluations = evaluations;
        this.submissions = submissions;
        this.events = events;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "${archforge.kafka.topics.evaluation-results:evaluation.results}",
            groupId = "${archforge.design.consumer-group:design-service-group}",
            containerFactory = "archforgeKafkaListenerContainerFactory"
    )
    @Transactional
    public void onResult(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        ArchitectureEvaluationEvent event;
        try {
            event = objectMapper.readValue(payload, ArchitectureEvaluationEvent.class);
        } catch (Exception e) {
            meterRegistry.counter("design.evaluation.unreadable").increment();
            throw new IllegalArgumentException(
                    "Unreadable evaluation result: " + e.getMessage(), e);
        }
        if (event == null || event.submissionId() == null) {
            meterRegistry.counter("design.evaluation.unidentified").increment();
            throw new IllegalArgumentException("Evaluation result carries no submissionId");
        }
        apply(event);
    }

    void apply(ArchitectureEvaluationEvent event) {
        var submission = submissions.findByIdForUpdate(parseUuid(event.submissionId())).orElse(null);
        if (submission == null) {
            meterRegistry.counter("design.evaluation.orphan").increment();
            log.warn("Evaluation {} references unknown submission {}", event.evaluationId(),
                    parseUuid(event.submissionId()));
            return;
        }
        if (!submission.attachSimulation(parseUuid(event.simulationId()))) {
            throw new IllegalArgumentException("Evaluation simulation does not match the submission's simulation");
        }

        UUID evaluationId = parseUuid(event.evaluationId());
        if (evaluationId == null) {
            evaluationId = UUID.randomUUID();
        }
        evaluations.upsert(new Evaluation(
                evaluationId,
                parseUuid(event.submissionId()),
                parseUuid(event.simulationId()),
                event.overallScore(),
                event.verdict(),
                event.summary(),
                event.strengths(),
                event.weaknesses(),
                event.recommendations(),
                event.bottlenecks(),
                toFindings(event.findings()),
                toReferences(event.references()),
                caveatsOf(event),
                event.model(),
                event.latencyMs(),
                event.promptTokens(),
                event.completionTokens(),
                event.degraded(),
                event.degradedReason(),
                event.evaluatedAt() == null ? java.time.Instant.now() : event.evaluatedAt()));

        // A completion signal alone can precede evaluation.results. Mark complete
        // only after the verdict itself has been stored in this transaction.
        submission.transitionTo(com.archforge.contracts.lifecycle.SubmissionStatus.COMPLETED);
        submissions.save(submission);

        meterRegistry.counter("design.evaluation.recorded", "verdict", event.verdict()).increment();
        if (event.degraded()) {
            meterRegistry.counter("design.evaluation.degraded").increment();
        }
        log.info("Recorded evaluation {} for submission {}: {}/{} ({} finding(s), model {}{})",
                evaluationId, parseUuid(event.submissionId()), event.overallScore(), event.verdict(),
                event.findings().size(), event.model(), event.degraded() ? ", degraded" : "");
    }

    @KafkaListener(
            topics = "${archforge.kafka.topics.evaluation-lifecycle:evaluation.lifecycle}",
            groupId = "${archforge.design.consumer-group:design-service-group}",
            containerFactory = "archforgeKafkaListenerContainerFactory"
    )
    @Transactional
    public void onEvaluationLifecycle(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        EvaluationLifecycleEvent event;
        try {
            event = objectMapper.readValue(payload, EvaluationLifecycleEvent.class);
        } catch (Exception e) {
            meterRegistry.counter("design.evaluation_lifecycle.unreadable").increment();
            throw new IllegalArgumentException(
                    "Unreadable evaluation lifecycle record: " + e.getMessage(), e);
        }
        if (event == null || event.submissionId() == null) {
            return;
        }

        int appended = events.append(
                parseUuid(event.eventId()),
                parseUuid(event.submissionId()),
                null,
                parseUuid(event.correlationId()),
                "evaluation." + event.status(),
                null,
                event.message(),
                "{}",
                event.occurredAt() == null ? java.time.Instant.now() : event.occurredAt());
        if (appended > 0) {
            meterRegistry.counter("design.evaluation_lifecycle.recorded", "status", event.status())
                    .increment();
            var submission = submissions.findByIdForUpdate(parseUuid(event.submissionId())).orElse(null);
            if (submission != null) {
                if (EvaluationLifecycleEvent.STATUS_STARTED.equals(event.status())) {
                    submission.transitionTo(com.archforge.contracts.lifecycle.SubmissionStatus.EVALUATING);
                } else if (EvaluationLifecycleEvent.STATUS_FAILED.equals(event.status())) {
                    submission.transitionTo(com.archforge.contracts.lifecycle.SubmissionStatus.FAILED);
                }
                submissions.save(submission);
            }
        }
    }

    /**
     * The caveats that travel with a verdict.
     *
     * <p>An evaluation that was degraded, or whose trace was partial, carries the reason in
     * {@code degradedReason} -- a sentence written for a reader, not a code. It is stored
     * verbatim, so the reason a score is less trustworthy is visible next to the score rather
     * than only in the evaluator's log.
     */
    private static List<String> caveatsOf(ArchitectureEvaluationEvent event) {
        List<String> caveats = new ArrayList<>();
        if (event.degraded() && event.degradedReason() != null
                && !event.degradedReason().isBlank()) {
            caveats.add(event.degradedReason());
        }
        return caveats;
    }

    private static List<Finding> toFindings(List<ArchitectureEvaluationEvent.Finding> findings) {
        List<Finding> mapped = new ArrayList<>();
        if (findings == null) {
            return mapped;
        }
        for (int i = 0; i < findings.size(); i++) {
            ArchitectureEvaluationEvent.Finding finding = findings.get(i);
            mapped.add(new Finding(i, finding.relatedNodeIds() == null
                    || finding.relatedNodeIds().isEmpty() ? null : finding.relatedNodeIds().get(0),
                    finding.severity(), finding.category(), finding.title(), finding.detail(),
                    finding.evidence(), finding.recommendation(), finding.relatedNodeIds()));
        }
        return mapped;
    }

    private static List<com.archforge.contracts.event.ArchitectureEvaluationEvent.KnowledgeReference>
            toReferences(List<com.archforge.contracts.event.ArchitectureEvaluationEvent.KnowledgeReference>
                                 references) {
        return references == null ? List.of() : references;
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
