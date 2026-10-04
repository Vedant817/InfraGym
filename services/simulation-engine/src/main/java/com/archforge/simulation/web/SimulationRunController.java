package com.archforge.simulation.web;

import com.archforge.contracts.lifecycle.ScenarioType;
import com.archforge.simulation.engine.NodeReport;
import com.archforge.simulation.engine.SimulationOutcome;
import com.archforge.simulation.event.TelemetryPublisher;
import com.archforge.simulation.service.SimulationOutcomeRegistry;
import com.archforge.simulation.service.SimulationRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only introspection over simulation runs.
 *
 * <p>Deliberately small and deliberately read-only. The engine's job is to consume
 * tasks and publish results; it does not accept simulation requests over HTTP, and it
 * does not store anything durable. A user submits through the gateway, and this
 * endpoint exists so that when they ask "why did my P99 come out at 4 seconds" the
 * answer is one GET rather than a log search through a minute-long trace.
 *
 * <p>Unauthenticated, like the compiler's actuator surface. It exposes a simulation id
 * the caller must already know and per-run measurements of an architecture the caller
 * submitted; it accepts no input and reaches nothing else. If a deployment needs it
 * closed, {@code archforge.security.enabled} gates it the same way it does everywhere
 * else in the platform.
 */
@RestController
@RequestMapping("/api/v1/simulations")
@RequiredArgsConstructor
@Tag(name = "Simulations", description = "Inspect completed and failed simulation runs")
public class SimulationRunController {

    private final SimulationOutcomeRegistry registry;
    private final SimulationRunService runService;
    private final TelemetryPublisher telemetryPublisher;

    @GetMapping
    @Operation(summary = "Recent runs, newest first")
    public List<Map<String, Object>> recent(
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        return registry.recent(bounded).stream().map(this::summarise).toList();
    }

    @GetMapping("/{simulationId}")
    @Operation(summary = "One run in full, including every component's percentiles")
    public ResponseEntity<Object> byId(@PathVariable String simulationId) {
        return registry.find(simulationId)
                .<ResponseEntity<Object>>map(outcome -> ResponseEntity.ok(detail(outcome)))
                // A failed run is a real answer, not a 404: the caller needs the reason
                // the run did not produce results. Only an id this service has never
                // seen is genuinely not found.
                .or(() -> registry.findFailure(simulationId)
                        .<ResponseEntity<Object>>map(failure -> ResponseEntity.status(
                                HttpStatus.CONFLICT).body(Map.of(
                                        "simulationId", failure.simulationId(),
                                        "submissionId", failure.submissionId() == null
                                                ? "" : failure.submissionId(),
                                        "status", failure.status().getValue(),
                                        "message", failure.message() == null ? "" : failure.message(),
                                        "failedAt", failure.failedAt().toString()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{simulationId}/nodes/{nodeId}")
    @Operation(summary = "One component's results within a run")
    public ResponseEntity<Object> node(@PathVariable String simulationId,
                                      @PathVariable String nodeId) {
        SimulationOutcome outcome = registry.find(simulationId).orElse(null);
        if (outcome == null) {
            return ResponseEntity.notFound().build();
        }
        NodeReport report = outcome.node(nodeId);
        // A known run with an unknown node is a 404 about the node, not about the run.
        if (report == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(GlobalExceptionHandler.ErrorResponse.of(
                        "UNKNOWN_NODE",
                        "Run " + simulationId + " has no component '" + nodeId + "'.",
                        "GET /api/v1/simulations/" + simulationId
                                + " lists every component in the run."));
        }
        return ResponseEntity.ok(component(outcome, report));
    }

    @GetMapping("/scenarios")
    @Operation(summary = "Scenarios this engine can execute, with their multipliers")
    public List<Map<String, Object>> scenarios() {
        return ScenarioType.all().stream()
                .map(scenario -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("value", scenario.getValue());
                    entry.put("displayName", scenario.getDisplayName());
                    entry.put("description", scenario.getDescription());
                    // "Extreme", not "peak": a collapse scenario's extreme is its trough, and a
        // field named baselineMultiplier reading 0.1 for a traffic collapse is a
        // number nobody can interpret without reading the enum's source.
        entry.put("extremeLoadMultiplier", scenario.getExtremeLoadMultiplier());
        entry.put("isBurst", scenario.isBurst());
                    entry.put("failureFraction", scenario.getFailureFraction());
                    entry.put("failureTarget", scenario.getFailureTarget().name());
                    entry.put("injectsFailures", scenario.injectsFailures());
                    return entry;
                })
                .toList();
    }

    @GetMapping("/engine")
    @Operation(summary = "Engine self-report: counters, buffer state and configuration")
    public Map<String, Object> engine() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runsCompleted", runService.runsCompleted());
        body.put("runsFailed", runService.runsFailed());
        body.put("retainedRuns", registry.size());
        body.put("telemetryPublishedSamples", telemetryPublisher.publishedSamples());
        body.put("telemetryDroppedSamples", telemetryPublisher.droppedSamples());
        body.put("telemetryBufferedSamples", telemetryPublisher.bufferedSamples());
        return body;
    }

    private Map<String, Object> summarise(SimulationOutcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("simulationId", outcome.simulationId());
        body.put("submissionId", outcome.submissionId());
        body.put("scenario", outcome.scenario().getValue());
        body.put("status", "completed");
        body.put("seed", outcome.seed());
        body.put("nominalRps", round(outcome.nominalRps()));
        body.put("tickCount", outcome.tickCount());
        body.put("tickMillis", outcome.tickMillis());
        body.put("nodeCount", outcome.nodeReports().size());
        body.put("worstNodeP99Ms", round(outcome.worstNodeP99Ms()));
        body.put("worstLatencyMs", round(outcome.worstLatencyMs()));
        body.put("completionRate", round(outcome.completionRate()));
        body.put("totalOffered", round(outcome.totalOffered()));
        body.put("totalServed", round(outcome.totalServed()));
        body.put("totalFailed", round(outcome.totalFailed()));
        // The third term of the identity, and without it the completion rate above is not
        // explainable: a run can be short of 100% with nothing failed, because the missing
        // requests are still queued behind a consumer that cannot keep up.
        body.put("residualBacklog", round(outcome.residualBacklog()));
        body.put("reconciles", outcome.reconciles());
        body.put("wallClockMs", outcome.wallClock().toMillis());
        body.put("startedAt", outcome.startedAt().toString());
        body.put("completedAt", outcome.completedAt().toString());
        return body;
    }

    private Map<String, Object> detail(SimulationOutcome outcome) {
        Map<String, Object> body = summarise(outcome);
        body.put("failures", outcome.failures().stream().map(failure -> Map.of(
                "nodeId", failure.nodeId(),
                "nodeType", failure.nodeType(),
                "mode", failure.mode(),
                "failureMode", failure.failureMode(),
                "startTick", failure.startTick(),
                "endTick", failure.endTick(),
                "blastRadius", failure.blastRadius(),
                "affectedNodeIds", failure.affectedNodeIds())).toList());
        body.put("warnings", outcome.warnings());
        body.put("nodes", outcome.nodeReports().stream().map(this::componentBody).toList());
        // Levels are multipliers of the graph's own capacity, and the resolved rps is
        // published alongside so a reader does not have to multiply it themselves.
        body.put("workload", Map.of(
                "nominalRps", round(outcome.nominalRps()),
                "peakMultiplier", round(outcome.workload().peakMultiplier()),
                "peakRps", round(outcome.workload().peakRps(outcome.nominalRps())),
                "phases", outcome.workload().phases().stream().map(phase -> Map.of(
                        "name", phase.name(),
                        "durationSeconds", phase.durationSeconds(),
                        "startMultiplier", round(phase.startMultiplier()),
                        "targetMultiplier", round(phase.targetMultiplier()),
                        "peakRps", round(phase.peakRps(outcome.nominalRps())),
                        "ramp", phase.ramp())).toList()));
        return body;
    }

    private Map<String, Object> component(SimulationOutcome outcome, NodeReport report) {
        Map<String, Object> body = componentBody(report);
        body.put("simulationId", outcome.simulationId());
        body.put("findings", report.findings());
        return body;
    }

    private Map<String, Object> componentBody(NodeReport report) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodeId", report.nodeId());
        body.put("nodeType", report.nodeType());
        body.put("label", report.label());
        body.put("p50LatencyMs", round(report.p50LatencyMs()));
        body.put("p95LatencyMs", round(report.p95LatencyMs()));
        body.put("p99LatencyMs", round(report.p99LatencyMs()));
        body.put("maxLatencyMs", round(report.maxLatencyMs()));
        body.put("avgErrorRate", round(report.avgErrorRate()));
        body.put("peakErrorRate", round(report.peakErrorRate()));
        body.put("peakThroughputRps", round(report.peakThroughputRps()));
        body.put("avgThroughputRps", round(report.avgThroughputRps()));
        body.put("peakUtilization", round(report.peakUtilization()));
        body.put("avgUtilization", round(report.avgUtilization()));
        body.put("avgCacheHitRate", round(report.avgCacheHitRate()));
        body.put("peakQueueDepth", round(report.peakQueueDepth()));
        body.put("maxQueueAgeMs", round(report.maxQueueAgeMs()));
        body.put("wasFailureTarget", report.wasFailureTarget());
        body.put("saturated", report.saturated());
        body.put("persistentlySaturated", report.persistentlySaturated());
        body.put("recoveryTimeMs", round(report.recoveryTimeMs()));
        body.put("failureTicks", report.failureTicks());
        body.put("sampleCount", report.sampleCount());
        body.put("unroutedBypassRequests", round(report.unroutedBypassRequests()));
        body.put("declaredRateRps", round(report.declaredRateRps()));
        // Both, because they differ for exactly the component where the difference is the
        // whole story: a queue declares 10 000 messages/second and can only ever take as
        // many as its consumer reads, which is the number a reader needs to size it.
        body.put("observedCeilingRps", round(report.observedCeilingRps()));
        return body;
    }

    /**
     * Rounds for display.
     *
     * <p>Three decimals is enough to see a regression and short enough that a chart
     * axis is not dominated by floating-point noise. Raw values are preserved in the
     * telemetry stream for anyone who needs them.
     */
    private static double round(double value) {
        return Double.isFinite(value) ? Math.round(value * 1000.0) / 1000.0 : 0;
    }
}
