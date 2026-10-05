package com.archforge.design.repository;

import com.archforge.design.domain.SubmissionEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The append-only event log.
 *
 * <p>Its only write is {@link #append}, and that method's behaviour on a duplicate event id
 * is the whole reason the table's primary key is the producer's id rather than a surrogate:
 * the insert is rejected, the caller learns it, and a redelivered lifecycle event leaves the
 * log exactly as it was. The pipeline is at-least-once by construction, and an audit trail
 * that double-counts a redelivery is not an audit trail.
 */
@Repository
public interface SubmissionEventRepository extends JpaRepository<SubmissionEventEntity, UUID> {

    /** @return true when the event was new; false when it had already been recorded */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO submission_events (event_id, submission_id, simulation_id,
                                          correlation_id, stage, status, message, details,
                                          occurred_at, received_at)
            VALUES (:eventId, :submissionId, :simulationId, :correlationId, :stage, :status,
                    :message, CAST(:details AS jsonb), :occurredAt, now())
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int append(@Param("eventId") UUID eventId,
               @Param("submissionId") UUID submissionId,
               @Param("simulationId") UUID simulationId,
               @Param("correlationId") UUID correlationId,
               @Param("stage") String stage,
               @Param("status") String status,
               @Param("message") String message,
               @Param("details") String details,
               @Param("occurredAt") Instant occurredAt);

    List<SubmissionEventEntity> findBySubmissionIdOrderByOccurredAtAsc(UUID submissionId);

    List<SubmissionEventEntity> findBySimulationIdOrderByOccurredAtAsc(UUID simulationId);

    boolean existsByEventId(UUID eventId);

    Optional<SubmissionEventEntity> findFirstBySubmissionIdOrderByOccurredAtDesc(UUID submissionId);

    /** Stages a submission passed through, in order. Useful for "where did it stop?". */
    @Query("SELECT e.stage FROM SubmissionEventEntity e WHERE e.submissionId = :submissionId "
            + "ORDER BY e.occurredAt ASC")
    List<String> stagesOf(@Param("submissionId") UUID submissionId);
}