package com.archforge.design.web;

import com.archforge.design.domain.Evaluation;
import com.archforge.design.domain.NodeMetric;
import com.archforge.design.domain.SimulationEntity;
import com.archforge.design.domain.SubmissionEntity;
import com.archforge.design.domain.SubmissionEventEntity;
import com.archforge.design.repository.EvaluationRepository;
import com.archforge.design.repository.NodeMetricRepository;
import com.archforge.design.repository.SimulationRepository;
import com.archforge.design.repository.SubmissionEventRepository;
import com.archforge.design.repository.SubmissionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything a user watches while a run happens, and afterwards.
 *
 * <p>The ownership check is on the way in, once, and everything below it is reached through
 * ids that were resolved from an owned submission. That ordering is deliberate: a
 * {@code simulationId} taken straight from a URL cannot be checked against an owner without
 * the join, so the request is authorised by submission id and the simulation is then looked
 * up from that submission rather than from the caller's word for which one it is.
 *
 * <p>Three endpoints rather than one, because they are read at very different rates and have
 * very different costs: progress is polled every second, measurements once, the verdict once.
 * Returning the verdict with the progress tick would mean re-serialising every finding on
 * every poll.
 */
@RestController
@RequestMapping("/api/v1/submissions")
@Slf4j
public class SubmissionController {

    private final SubmissionRepository submissions;
    private final SubmissionEventRepository events;
    private final SimulationRepository simulations;
    private final NodeMetricRepository metrics;
    private final EvaluationRepository evaluations;

    public SubmissionController(SubmissionRepository submissions,
                                SubmissionEventRepository events,
                                SimulationRepository simulations,
                                NodeMetricRepository metrics,
                                EvaluationRepository evaluations) {
        this.submissions = submissions;
        this.events = events;
        this.simulations = simulations;
        this.metrics = metrics;
        this.evaluations = evaluations;
    }

    @GetMapping
    public List<Dtos.SubmissionSummary> list(Authentication authentication,
                                             @RequestParam(defaultValue = "50") int limit) {
        return submissions
                .findByUserIdOrderBySubmittedAtDesc(authentication.getName(),
                        org.springframework.data.domain.PageRequest.of(0,
                                Math.max(1, Math.min(limit, 200))))
                .stream()
                .map(submission -> toSummary(submission, lastEvent(submission.getId())))
                .toList();
    }

    /**
     * The progress view.
     *
     * <p>Returns the submission, its stages and whatever of the run and the verdict exists so
     * far. A run that has not finished has no evaluation and no simulation, and that is
     * reported as absent rather than as an empty result -- "not yet" and "nothing was found"
     * are different answers and a client that cannot tell them apart shows an empty report
     * for a run that is still going.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Dtos.SubmissionDetail> get(Authentication authentication,
                                                     @PathVariable UUID id) {
        SubmissionEntity submission = submissions.findByIdAndUserId(id, authentication.getName())
                .orElse(null);
        if (submission == null) {
            return ResponseEntity.notFound().build();
        }
        List<Dtos.StageEvent> stages = events
                .findBySubmissionIdOrderByOccurredAtAsc(id).stream()
                .map(event -> new Dtos.StageEvent(event.getStage(), event.getStatus(),
                        event.getMessage(), event.getOccurredAt(), event.getReceivedAt()))
                .toList();
        return ResponseEntity.ok(new Dtos.SubmissionDetail(
                toSummary(submission, lastEvent(id)),
                stages,
                simulationOf(submission).map(SubmissionController::toSimulationSummary).orElse(null),
                latestEvaluation(submission).map(SubmissionController::toEvaluationView).orElse(null)));
    }

    /** One component's per-window series, for a chart. */
    @GetMapping("/{id}/metrics/{nodeId}/series")
    public ResponseEntity<List<NodeMetric.WindowMetric>> series(
            Authentication authentication,
            @PathVariable UUID id,
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "latency_ms") String metric) {
        SubmissionEntity submission = submissions.findByIdAndUserId(id, authentication.getName())
                .orElse(null);
        if (submission == null || submission.getSimulationId() == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(metrics.findWindowSeries(submission.getSimulationId(), nodeId, metric));
    }

    /**
     * The measurement view: the run's totals and every component's figures.
     *
     * <p>Also reports the run's own caveats, and whether its accounting reconciles. A stored
     * P99 with no note that 40% of the trace was missing is a number nobody can act on, and
     * the reader has to be able to see the qualification next to the figure rather than having
     * to go looking for it.
     */
    @GetMapping("/{id}/measurements")
    public ResponseEntity<Dtos.MeasurementView> measurements(Authentication authentication,
                                                             @PathVariable UUID id) {
        SubmissionEntity submission = submissions.findByIdAndUserId(id, authentication.getName())
                .orElse(null);
        if (submission == null) {
            return ResponseEntity.notFound().build();
        }
        var simulation = simulationOf(submission);
        if (simulation.isEmpty() || submission.getSimulationId() == null) {
            return ResponseEntity.notFound().build();
        }
        List<NodeMetric> nodes = metrics.findBySimulation(submission.getSimulationId());
        String worstNodeId = nodes.isEmpty() ? null : nodes.get(0).nodeId();
        double worstP99 = nodes.isEmpty() ? 0 : nodes.get(0).p99Ms();
        return ResponseEntity.ok(new Dtos.MeasurementView(
                toSimulationSummary(simulation.get()),
                nodes.stream().map(SubmissionController::toNodeMetric).toList(),
                worstNodeId, worstP99));
    }

    @GetMapping("/{id}/evaluation")
    public ResponseEntity<Dtos.EvaluationView> evaluation(Authentication authentication,
                                                          @PathVariable UUID id) {
        SubmissionEntity submission = submissions.findByIdAndUserId(id, authentication.getName())
                .orElse(null);
        if (submission == null) {
            return ResponseEntity.notFound().build();
        }
        return latestEvaluation(submission)
                .map(evaluation -> ResponseEntity.ok(toEvaluationView(evaluation)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private java.util.Optional<SimulationEntity> simulationOf(SubmissionEntity submission) {
        return simulations.findBySubmissionId(submission.getId())
                .or(() -> submission.getSimulationId() == null
                        ? java.util.Optional.empty()
                        : simulations.findById(submission.getSimulationId()));
    }

    private java.util.Optional<Evaluation> latestEvaluation(SubmissionEntity submission) {
        return evaluations.findLatestForSubmission(submission.getId());
    }

    private SubmissionEventEntity lastEvent(UUID submissionId) {
        return events.findFirstBySubmissionIdOrderByOccurredAtDesc(submissionId).orElse(null);
    }

    private Dtos.SubmissionSummary toSummary(SubmissionEntity submission,
                                             SubmissionEventEntity lastEvent) {
        return new Dtos.SubmissionSummary(
                submission.getId(), submission.getDesignId(), submission.getSimulationId(),
                submission.getStatus().getValue(), submission.getScenario(),
                submission.getScenarioNotes(), submission.getSubmittedAt(),
                submission.getCompletedAt(),
                lastEvent == null ? submission.getStatus().getValue() : lastEvent.getStage(),
                lastEvent == null ? null : lastEvent.getMessage());
    }

    static Dtos.SimulationSummary toSimulationSummary(SimulationEntity simulation) {
        return new Dtos.SimulationSummary(
                simulation.getId(), simulation.getScenario(), simulation.getStatus(),
                simulation.getTickCount(), simulation.getTotalTicks(), simulation.getTickMillis(),
                simulation.truncated(),
                simulation.getOffered(), simulation.getServed(), simulation.getFailed(),
                simulation.getQueued(), simulation.completionRate(), simulation.reconciles(),
                readWarnings(simulation.getWarnings()),
                simulation.getStartedAt(), simulation.getCompletedAt());
    }

    static Dtos.NodeMetricView toNodeMetric(NodeMetric metric) {
        return new Dtos.NodeMetricView(metric.nodeId(), metric.nodeType(), metric.measured(),
                metric.sampleCount(), metric.p50Ms(), metric.p95Ms(), metric.p99Ms(),
                metric.maxMs(), metric.avgErrorRate(), metric.peakErrorRate(), metric.peakRps(),
                metric.avgRps(), metric.peakUtilization(), metric.avgCacheHitRate(),
                metric.peakQueueDepth(), metric.saturated());
    }

    static Dtos.EvaluationView toEvaluationView(Evaluation evaluation) {
        Evaluation.Finding worst = evaluation.worstFinding();
        return new Dtos.EvaluationView(
                evaluation.id(), evaluation.overallScore(), evaluation.verdict(),
                evaluation.summary(), evaluation.degraded(), evaluation.degradedReason(),
                evaluation.model(), evaluation.latencyMs(),
                worst == null ? null : worst.title(),
                evaluation.strengths(), evaluation.weaknesses(), evaluation.recommendations(),
                evaluation.bottlenecks().stream()
                        .map(bottleneck -> new Dtos.BottleneckView(bottleneck.nodeId(),
                                bottleneck.metric(), bottleneck.observedValue(), bottleneck.unit(),
                                bottleneck.thresholdValue(), bottleneck.explanation()))
                        .toList(),
                evaluation.findings().stream()
                        .map(finding -> new Dtos.FindingView(finding.nodeId(),
                                finding.severity() == null ? "MEDIUM" : finding.severity().name(),
                                finding.category(), finding.title(), finding.detail(),
                                finding.evidence(), finding.recommendation(),
                                finding.relatedNodeIds()))
                        .toList(),
                evaluation.references().stream()
                        .map(reference -> reference.title() + " (similarity "
                                + reference.similarity() + ")")
                        .toList(),
                evaluation.warnings(),
                evaluation.evaluatedAt());
    }

    private static List<String> readWarnings(String json) {
        if (json == null || json.isBlank() || "[]".equals(json) || "null".equals(json)) {
            return List.of();
        }
        return List.of(json);
    }
}