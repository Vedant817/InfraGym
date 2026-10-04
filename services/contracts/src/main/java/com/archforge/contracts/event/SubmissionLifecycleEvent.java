package com.archforge.contracts.event;

import com.archforge.contracts.lifecycle.SubmissionStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A submission's progress through the pipeline, on {@code submission.lifecycle}.
 *
 * <p>Every stage publishes here and <em>one</em> service — the design service —
 * is the only writer of the submission row. That split matters: previously the
 * status lived in a Caffeine cache inside the compiler, so it was lost on
 * restart, invisible to the frontend, and unreachable for the two services that
 * actually knew the answer. An event per transition means the status is durable,
 * ordered, and correct regardless of which service noticed.
 *
 * <p>{@code SubmissionStatus.canTransitionTo} rejects out-of-order deliveries, so
 * a late {@code COMPILING} arriving after {@code SIMULATING} cannot rewind the
 * user's progress bar.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubmissionLifecycleEvent(
        String eventId,
        String correlationId,
        String submissionId,
        String simulationId,
        String userId,
        String designId,
        String stage,
        SubmissionStatus status,
        String message,
        Map<String, Object> details,
        Instant occurredAt) {

    /** Logical stage names, so consumers can filter without string-matching messages. */
    public static final String STAGE_GATEWAY = "gateway";
    public static final String STAGE_COMPILER = "graph-compiler";
    public static final String STAGE_SIMULATION = "simulation-engine";
    public static final String STAGE_AGGREGATION = "telemetry-aggregator";
    public static final String STAGE_EVALUATION = "ai-evaluator";

    public SubmissionLifecycleEvent {
        if (eventId == null) {
            eventId = UUID.randomUUID().toString();
        }
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
        if (details == null) {
            details = Map.of();
        }
        if (status == null) {
            status = SubmissionStatus.RECEIVED;
        }
    }

    public static SubmissionLifecycleEvent of(String correlationId,
                                             String submissionId,
                                             String simulationId,
                                             String userId,
                                             String designId,
                                             String stage,
                                             SubmissionStatus status,
                                             String message) {
        return new SubmissionLifecycleEvent(UUID.randomUUID().toString(), correlationId,
                submissionId, simulationId, userId, designId, stage, status, message,
                Map.of(), Instant.now());
    }

    public static SubmissionLifecycleEvent withDetails(String correlationId,
                                                       String submissionId,
                                                       String simulationId,
                                                       String userId,
                                                       String designId,
                                                       String stage,
                                                       SubmissionStatus status,
                                                       String message,
                                                       Map<String, Object> details) {
        return new SubmissionLifecycleEvent(UUID.randomUUID().toString(), correlationId,
                submissionId, simulationId, userId, designId, stage, status, message,
                details == null ? Map.of() : details, Instant.now());
    }
}
