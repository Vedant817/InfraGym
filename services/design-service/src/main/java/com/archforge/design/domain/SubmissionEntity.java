package com.archforge.design.domain;

import com.archforge.contracts.canvas.Topology;
import com.archforge.contracts.lifecycle.SubmissionStatus;
import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One request to run a design.
 *
 * <p>The submitted topology is <em>frozen into this row</em>, not referenced back to the
 * design. That is deliberate: a design can be edited after it is submitted, so re-reading
 * the design to explain a run would describe a design that is not the one that was
 * measured. Every answer about a completed run has to be answerable against what was
 * actually run.
 *
 * <p>{@code status} is a projection, not the truth. The truth is {@link SubmissionEventEntity}
 * -- an append-only log keyed by the producer's event id, so a redelivered event is rejected
 * by the database rather than by application logic that can be skipped. This row exists so
 * that "list my submissions" is one indexed query instead of a fold over the log.
 */
@Entity
@Table(name = "submissions", indexes = {
        @Index(name = "idx_submissions_user", columnList = "userId, submittedAt"),
        @Index(name = "idx_submissions_design", columnList = "designId, submittedAt"),
        @Index(name = "idx_submissions_simulation", columnList = "simulationId")
})
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class SubmissionEntity {

    @Id
    private UUID id;

    @Column(name = "design_id", nullable = false)
    private UUID designId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "simulation_id")
    private UUID simulationId;

    @Column(name = "correlation_id")
    private UUID correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SubmissionStatus status = SubmissionStatus.RECEIVED;

    @Column(nullable = false, length = 64)
    private String scenario;

    @Column(name = "scenario_notes", columnDefinition = "text")
    private String scenarioNotes;

    /**
     * The submitted topology, as JSON text.
     *
     * <p>{@code @JdbcTypeCode(SqlTypes.JSON)} is required, not decorative. Without it
     * Hibernate binds this as a {@code varchar} and Postgres refuses with "column topology is
     * of type jsonb but expression is of type character varying" -- so the one call that starts
     * the entire pipeline failed with a 500. A jsonb column cannot be written as a string
     * parameter; the driver has to be told it is JSON.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String topology;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected SubmissionEntity(UUID id, UUID designId, String userId, String scenario,
                               String scenarioNotes, String topology, Instant submittedAt,
                               UUID correlationId) {
        this.id = id == null ? UUID.randomUUID() : id;
        this.designId = designId;
        this.userId = userId;
        this.scenario = scenario;
        this.scenarioNotes = scenarioNotes;
        this.topology = topology;
        this.submittedAt = submittedAt == null ? Instant.now() : submittedAt;
        this.correlationId = correlationId == null ? this.id : correlationId;
        this.status = SubmissionStatus.RECEIVED;
    }

    public static SubmissionEntity create(UUID id, UUID designId, String userId, String scenario,
                                          String scenarioNotes, String topologyJson,
                                          Topology submittedTopology, Instant submittedAt,
                                          UUID correlationId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("A submission must have an owner");
        }
        if (designId == null) {
            throw new IllegalArgumentException("A submission must reference a design");
        }
        return new SubmissionEntity(id, designId, userId,
                scenario == null || scenario.isBlank() ? "normal" : scenario,
                scenarioNotes,
                topologyJson == null ? "{}" : topologyJson,
                submittedAt, correlationId);
    }

    /**
     * Moves to a status.
     *
     * <p>Never moves backwards. Lifecycle events are delivered at least once and, across two
     * topics, in no guaranteed order relative to each other -- so an event replayed after the
     * run completed must not reopen it. Without this, a redelivered {@code SIMULATING} turns a
     * finished submission back into one that appears to be running, and its frontend spinner
     * starts again for a simulation that ended minutes ago.
     */
    public boolean transitionTo(SubmissionStatus next) {
        if (next == null || next == this.status) {
            return false;
        }
        if (this.status.isTerminal()) {
            return false;
        }
        // Different Kafka topics can arrive out of order. Allow forward jumps
        // when an intermediate event was missed, but never rewind progress.
        if (!next.isTerminal() && next.ordinal() <= this.status.ordinal()) {
            return false;
        }
        this.status = next;
        if (next.isTerminal()) {
            this.completedAt = Instant.now();
        }
        return true;
    }

    /**
     * Records the simulation the compiler created for this submission.
     *
     * <p>Only ever sets it once. A second, different simulation id for the same submission
     * would mean the submission was run twice, which is a real event but not one this
     * projection should silently adopt -- the caller logs it and the original id stands.
     */
    public boolean attachSimulation(UUID candidate) {
        if (candidate == null) {
            return false;
        }
        if (simulationId == null) {
            this.simulationId = candidate;
            return true;
        }
        return simulationId.equals(candidate);
    }

    public boolean ownedBy(String candidateUser) {
        return userId != null && userId.equals(candidateUser);
    }
}
