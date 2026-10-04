package com.archforge.contracts.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * An evaluation lifecycle transition on {@code evaluation.lifecycle}.
 *
 * <p>Kept separate from {@link ArchitectureEvaluationEvent} so a user watching
 * a run sees "evaluating…" the moment the simulation finishes, rather than after
 * the model has already returned.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluationLifecycleEvent(
        String eventId,
        String correlationId,
        String evaluationId,
        String simulationId,
        String submissionId,
        String status,
        String message,
        Instant occurredAt) {

    public static final String STATUS_STARTED = "started";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_DEGRADED = "degraded";

    public EvaluationLifecycleEvent {
        if (eventId == null) {
            eventId = UUID.randomUUID().toString();
        }
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }

    public static EvaluationLifecycleEvent of(String correlationId, String evaluationId,
                                              String simulationId, String submissionId,
                                              String status, String message) {
        return new EvaluationLifecycleEvent(UUID.randomUUID().toString(), correlationId,
                evaluationId, simulationId, submissionId, status, message, Instant.now());
    }
}
