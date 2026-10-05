package com.archforge.design.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the frontend reads.
 *
 * <p>Records rather than entities, deliberately: an entity handed to Jackson serialises its
 * internals, and the moment a field is added to a row the API shape changes without anyone
 * deciding it should. A DTO is the API, and it can only change by being edited.
 *
 * <p>Every one of these is owner-scoped. There is no "get by id without a user" anywhere in
 * this package, so a missing ownership check cannot be forgotten at one call site.
 */
public final class Dtos {

    private Dtos() {
    }

    /** A row in the design list. Cheap to build: no topology. */
    public record DesignSummary(
            UUID id,
            String name,
            String description,
            String scenario,
            int nodeCount,
            int edgeCount,
            long revision,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {
    }

    /** A design with its topology, for the canvas editor. */
    public record DesignDetail(
            UUID id,
            String name,
            String description,
            String scenario,
            long revision,
            Object topology,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {
    }

    /** A submission's current state. */
    public record SubmissionSummary(
            UUID id,
            UUID designId,
            UUID simulationId,
            String status,
            String scenario,
            String scenarioNotes,
            java.time.Instant submittedAt,
            java.time.Instant completedAt,
            String currentStage,
            String currentMessage) {
    }

    /**
     * One stage a submission passed through.
     *
     * <p>{@code details} is the lifecycle event's structured payload, an empty map when
     * the producer sent none. It exists so a rejection can say <em>which</em> node was
     * refused and why: the compiler publishes {@code findings:[{field, code, message}]}
     * on a rejection, and without this the record carried only the prose
     * "Topology failed validation".
     */
    public record StageEvent(
            String stage,
            String status,
            String message,
            java.time.Instant occurredAt,
            java.time.Instant receivedAt,
            Map<String, Object> details) {
    }

    /** A submission with its history, for the progress view. */
    public record SubmissionDetail(
            SubmissionSummary submission,
            List<StageEvent> stages,
            SimulationSummary simulation,
            EvaluationView evaluation) {
    }

    /** The run's headline figures. */
    public record SimulationSummary(
            UUID id,
            String scenario,
            String status,
            long tickCount,
            long totalTicks,
            long tickMillis,
            boolean truncated,
            double offered,
            double served,
            double failed,
            double queued,
            double completionRate,
            boolean reconciles,
            List<String> warnings,
            java.time.Instant startedAt,
            java.time.Instant completedAt) {
    }

    /** One component's figures, worst P99 first. */
    public record NodeMetricView(
            String nodeId,
            String nodeType,
            boolean measured,
            long sampleCount,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double maxMs,
            double avgErrorRate,
            double peakErrorRate,
            double peakRps,
            double avgRps,
            double peakUtilization,
            double avgCacheHitRate,
            long peakQueueDepth,
            boolean saturated) {
    }

    /** The full measurement view: totals, every component, and each one's worst figure. */
    public record MeasurementView(
            SimulationSummary simulation,
            List<NodeMetricView> nodes,
            String worstNodeId,
            double worstP99Ms) {
    }

    /** The verdict, and whether it is the whole assessment. */
    public record EvaluationView(
            UUID id,
            int overallScore,
            String verdict,
            String summary,
            boolean degraded,
            String degradedReason,
            String model,
            long latencyMs,
            String worstFinding,
            List<String> strengths,
            List<String> weaknesses,
            List<String> recommendations,
            List<BottleneckView> bottlenecks,
            List<FindingView> findings,
            List<String> references,
            List<String> warnings,
            java.time.Instant evaluatedAt) {
    }

    /** The component limiting the run, with the measurement that proves it. */
    public record BottleneckView(
            String nodeId,
            String metric,
            double observedValue,
            String unit,
            double thresholdValue,
            String explanation) {
    }

    /** One finding, always with the evidence behind it. */
    public record FindingView(
            String nodeId,
            String severity,
            String category,
            String title,
            String detail,
            String evidence,
            String recommendation,
            List<String> relatedNodeIds) {
    }

    /** A request to create a design. */
    public record CreateDesignRequest(
            String name,
            String description,
            String scenario,
            com.archforge.contracts.canvas.Topology topology) {
    }

    /** A request to submit a design for simulation. */
    public record SubmitRequest(
            String scenario,
            String scenarioNotes,
            String sessionId) {
    }

    /** A request to save a topology edit. */
    public record UpdateTopologyRequest(
            com.archforge.contracts.canvas.Topology topology) {
    }
}