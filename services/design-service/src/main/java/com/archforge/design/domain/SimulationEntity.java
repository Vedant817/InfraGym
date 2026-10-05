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
 * One simulation run's figures, as the engine and aggregator published them.
 *
 * <p>The four request totals are columns, not a JSON blob, for one reason: they form the
 * identity {@code offered == served + failed + queued}, and the only way a systematic
 * miscount is ever going to be noticed is by asserting it over history in SQL. Buried in
 * JSON it is readable by exactly the code that wrote it and by nobody who wants to check.
 */
@Entity
@Table(name = "simulations", indexes = {
        @Index(name = "idx_simulations_submission", columnList = "submissionId")
})
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class SimulationEntity {

    @Id
    private UUID id;

    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    @Column(nullable = false, length = 64)
    private String scenario = "normal";

    @Column(nullable = false, length = 32)
    private String status = "unknown";

    @Column(name = "tick_count", nullable = false)
    private long tickCount;

    @Column(name = "total_ticks", nullable = false)
    private long totalTicks;

    @Column(name = "tick_millis", nullable = false)
    private long tickMillis;

    /** @JdbcTypeCode because a jsonb column cannot be bound as a varchar parameter. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String warnings;

    @Column(nullable = false)
    private double offered;

    @Column(nullable = false)
    private double served;

    @Column(nullable = false)
    private double failed;

    @Column(nullable = false)
    private double queued;

    /** @JdbcTypeCode because a jsonb column cannot be bound as a varchar parameter. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "graph_metrics", columnDefinition = "jsonb")
    private String graphMetrics;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected SimulationEntity(UUID id, UUID submissionId, String scenario) {
        this.id = id == null ? UUID.randomUUID() : id;
        this.submissionId = submissionId;
        this.scenario = scenario == null ? "normal" : scenario;
    }

    public static SimulationEntity of(UUID id, UUID submissionId, String scenario) {
        return new SimulationEntity(id, submissionId, scenario);
    }

    /**
     * Applies a run's figures.
     *
     * <p>Overwrites rather than accumulates. The aggregator publishes one summary per run and
     * it is authoritative, so a second application of the same run must produce the same row;
     * accumulating would double every total on a redelivery, which is the exact accounting
     * error this schema exists to make checkable.
     */
    public void apply(String status, long tickCount, long totalTicks, long tickMillis,
                      double offered, double served, double failed, double queued,
                      String warnings, String graphMetrics,
                      Instant startedAt, Instant completedAt) {
        if (status != null && !status.isBlank()) {
            this.status = status;
        }
        this.tickCount = tickCount;
        this.totalTicks = totalTicks > 0 ? totalTicks : tickCount;
        this.tickMillis = tickMillis;
        this.offered = offered;
        this.served = served;
        this.failed = failed;
        this.queued = queued;
        if (warnings != null) {
            this.warnings = warnings;
        }
        if (graphMetrics != null) {
            this.graphMetrics = graphMetrics;
        }
        if (startedAt != null) {
            this.startedAt = startedAt;
        }
        if (completedAt != null) {
            this.completedAt = completedAt;
        }
    }

    /**
     * Whether the run's own four totals account for every request.
     *
     * <p>Exposed rather than assumed, because a run that does not reconcile is reporting at
     * least one figure in the wrong place and every completion rate derived from it is
     * wrong. Whether that is surfaced or only logged is the caller's decision.
     */
    public boolean reconciles() {
        if (offered <= 0) {
            return true;
        }
        return Math.abs(offered - (served + failed + queued)) <= 1.0;
    }

    /**
     * Whether the run was cut short.
     *
     * <p>Distinct from incomplete: a truncated run's later phases never happened, so its
     * figures describe a shorter run than the one that was asked for. Without the requested
     * total in the same row, "40 ticks" is indistinguishable from "40 of 60".
     */
    public boolean truncated() {
        return totalTicks > 0 && tickCount < totalTicks;
    }

    /** Fraction of offered load that reached a terminal component. */
    public double completionRate() {
        return offered <= 0 ? 0 : served / offered;
    }
}