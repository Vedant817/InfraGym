package com.archforge.design.domain;

import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One lifecycle transition, appended. Never updated, never deleted.
 *
 * <p>{@code eventId} is the producer's id and the primary key. That single decision is the
 * whole idempotency story: a redelivered event -- and the pipeline is at-least-once by
 * construction -- collides with an existing row and the insert is rejected by the database.
 * An application-level "have I seen this?" check is the same idea and worse, because it can
 * be skipped, it can be raced by two consumer threads, and it lives in code rather than in
 * the schema where nothing can forget it.
 *
 * <p>{@code receivedAt} is kept alongside {@code occurredAt} because they can disagree by a
 * wide margin. A run that finished before the aggregator caught up produces events whose
 * occurredAt is older than the receivedAt of the event that came first -- and when a reader
 * asks "how long did this take", conflating the two answers a different question.
 */
@Entity
@Table(name = "submission_events", indexes = {
        @Index(name = "idx_submission_events_submission",
                columnList = "submissionId, occurredAt, eventId"),
        @Index(name = "idx_submission_events_simulation", columnList = "simulationId")
})
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class SubmissionEventEntity {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    @Column(name = "simulation_id")
    private UUID simulationId;

    @Column(name = "correlation_id")
    private UUID correlationId;

    @Column(nullable = false, length = 64)
    private String stage;

    @Column(length = 32)
    private String status;

    @Column(columnDefinition = "text")
    private String message;

    /** @JdbcTypeCode because a jsonb column cannot be bound as a varchar parameter. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String details;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected SubmissionEventEntity(UUID eventId, UUID submissionId, UUID simulationId,
                                    UUID correlationId, String stage, String status,
                                    String message, String details, Instant occurredAt) {
        this.eventId = eventId == null ? UUID.randomUUID() : eventId;
        this.submissionId = submissionId;
        this.simulationId = simulationId;
        this.correlationId = correlationId;
        this.stage = stage;
        this.status = status;
        this.message = message;
        this.details = details;
        this.occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        this.receivedAt = Instant.now();
    }

    public static SubmissionEventEntity of(UUID eventId, UUID submissionId, UUID simulationId,
                                           UUID correlationId, String stage, String status,
                                           String message, String details, Instant occurredAt) {
        return new SubmissionEventEntity(eventId, submissionId, simulationId, correlationId,
                stage, status, message, details, occurredAt);
    }

    /** Whether this event completes the submission. */
    public boolean terminal() {
        return status != null && ("completed".equalsIgnoreCase(status)
                || "failed".equalsIgnoreCase(status)
                || "rejected".equalsIgnoreCase(status));
    }
}