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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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

    /**
     * Error code distinguishing "this run has not reached the stage that produces
     * measurements" from "this id does not exist".
     *
     * <p>The two used to share a bare 404. A submission the caller just created, on a
     * run still compiling, therefore answered "not found", which is both alarming
     * and wrong: the submission exists and the caller owns it. Ownership stays a 404
     * — answering 403 would confirm an id exists, which is exactly what an
     * enumeration of somebody else's ids wants — so the distinction is carried by an
     * explicit code the client can branch on.
     */
    static final String MEASUREMENTS_NOT_READY = "MEASUREMENTS_NOT_READY";

    /** Same distinction for the evaluation endpoint. */
    static final String EVALUATION_NOT_READY = "EVALUATION_NOT_READY";

    /** Code for a run the compiler refused, which will never produce an evaluation. */
    static final String EVALUATION_REJECTED = "EVALUATION_REJECTED";

    private final SubmissionRepository submissions;
    private final SubmissionEventRepository events;
    private final SimulationRepository simulations;
    private final NodeMetricRepository metrics;
    private final EvaluationRepository evaluations;
    private final ObjectMapper objectMapper;

    public SubmissionController(SubmissionRepository submissions,
                                SubmissionEventRepository events,
                                SimulationRepository simulations,
                                NodeMetricRepository metrics,
                                EvaluationRepository evaluations,
                                ObjectMapper objectMapper) {
        this.submissions = submissions;
        this.events = events;
        this.simulations = simulations;
        this.metrics = metrics;
        this.evaluations = evaluations;
        this.objectMapper = objectMapper;
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
                        event.getMessage(), event.getOccurredAt(), event.getReceivedAt(),
                        readDetails(event.getDetails())))
                .toList();
        return ResponseEntity.ok(new Dtos.SubmissionDetail(
                toSummary(submission, lastEvent(id)),
                stages,
                simulationOf(submission).map(this::toSimulationSummary).orElse(null),
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
    public ResponseEntity<?> measurements(Authentication authentication,
                                          @PathVariable UUID id) {
        SubmissionEntity submission = submissions.findByIdAndUserId(id, authentication.getName())
                .orElse(null);
        if (submission == null) {
            // Not this account's, or not a real id. A 403 would confirm the id exists,
            // which is what an enumeration of somebody else's ids wants.
            return ResponseEntity.notFound().build();
        }
        var simulation = simulationOf(submission);
        if (simulation.isEmpty() || submission.getSimulationId() == null) {
            return notReady(submission, MEASUREMENTS_NOT_READY,
                    "This run has not produced measurements yet. They appear once the simulation reaches aggregation.");
        }
        List<NodeMetric> nodes = metrics.findBySimulation(submission.getSimulationId());
        String worstNodeId = nodes.isEmpty() ? null : nodes.get(0).nodeId();
        double worstP99 = nodes.isEmpty() ? 0 : nodes.get(0).p99Ms();
        return ResponseEntity.ok(new Dtos.MeasurementView(
                toSimulationSummary(simulation.get()),
                nodes.stream().map(SubmissionController::toNodeMetric).toList(),
                worstNodeId, worstP99));
    }

    /**
     * The verdict, or the reason there is not one yet.
     *
     * <p>Three outcomes that used to collapse into one 404. A run the compiler
     * rejected will never have an evaluation, and saying "not found" for it hides the
     * real answer — fix the topology — behind what reads like a missing resource. A
     * run still in progress is a different answer again, and one that finished
     * without a verdict genuinely has nothing to return.
     */
    @GetMapping("/{id}/evaluation")
    public ResponseEntity<?> evaluation(Authentication authentication,
                                        @PathVariable UUID id) {
        SubmissionEntity submission = submissions.findByIdAndUserId(id, authentication.getName())
                .orElse(null);
        if (submission == null) {
            return ResponseEntity.notFound().build();
        }
        var stored = latestEvaluation(submission);
        if (stored.isPresent()) {
            return ResponseEntity.ok(toEvaluationView(stored.get()));
        }
        if (submission.getStatus() == com.archforge.contracts.lifecycle.SubmissionStatus.REJECTED) {
            return notReady(submission, EVALUATION_REJECTED,
                    "This run was rejected by the compiler, so no evaluation was produced. "
                            + "Fix the reported topology problems and start a new run.");
        }
        if (submission.getStatus() == null || !submission.getStatus().isTerminal()) {
            return notReady(submission, EVALUATION_NOT_READY,
                    "This run has not been evaluated yet. The verdict appears once evaluation completes.");
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * A 404 carrying a machine-readable code, so the client can tell "not yet" from
     * "never" from "not yours" without parsing the prose.
     */
    private static ResponseEntity<ProblemDetail> notReady(SubmissionEntity submission, String code,
                                                          String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail);
        problem.setTitle("Results are not available for this run yet");
        problem.setProperty("code", code);
        problem.setProperty("submissionId", submission.getId());
        problem.setProperty("status", submission.getStatus() == null ? null : submission.getStatus().getValue());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
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

    Dtos.SimulationSummary toSimulationSummary(SimulationEntity simulation) {
        return new Dtos.SimulationSummary(
                simulation.getId(), simulation.getScenario(), simulation.getStatus(),
                simulation.getTickCount(), simulation.getTotalTicks(), simulation.getTickMillis(),
                simulation.truncated(),
                simulation.getOffered(), simulation.getServed(), simulation.getFailed(),
                simulation.getQueued(), simulation.completionRate(), simulation.reconciles(),
                readWarnings(simulation.getWarnings(), objectMapper),
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

    /**
 * The stored warnings as a list of strings.
     *
     * <p>The column holds a JSON array produced by the aggregator, so it has to be
     * <em>parsed</em>. Wrapping the raw text in {@code List.of(json)} instead turned
     * {@code ["a","b"]} into the single element {@code ["[\"a\",\"b\"]"]}, which the UI
     * then rendered as literal backslash-quote-bracket text — the caveat existed, was
     * stored correctly, and reached the user unreadable. Unparseable content is
     * reported as one warning naming itself rather than dropped, because a caveat that
     * vanishes silently is the failure this whole field exists to prevent.
     */
    private static List<String> readWarnings(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank() || "[]".equals(json) || "null".equals(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("Stored simulation warnings are not a JSON array of strings: {}", json, e);
            return List.of("Stored warnings could not be decoded and are shown verbatim: " + json);
        }
    }

    /**
     * A stage event's stored details, as a map.
     *
     * <p>Parsed rather than passed through as text, so the client can render per-node
     * findings instead of displaying a JSON string. Unreadable or non-object content
     * yields an empty map: the event itself is still worth recording, and losing the
     * optional detail is not a reason to fail the request.
     */
    private Map<String, Object> readDetails(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (Exception e) {
            log.warn("Stored lifecycle details are not a JSON object: {}", json, e);
            return Map.of();
        }
    }
}