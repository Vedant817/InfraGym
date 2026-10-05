package com.archforge.design.repository;

import com.archforge.design.domain.SubmissionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Submissions and their event log.
 *
 * <p>As with designs, every read is owner-filtered. A submission id is just as much a
 * bearer reference as a design id, and a submission carries a user's architecture, so an
 * unfiltered lookup is a leak of somebody's design rather than a stray row.
 */
public interface SubmissionRepository extends JpaRepository<SubmissionEntity, UUID> {

    /** Serialize projections from separate Kafka topics within their transactions. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT s FROM SubmissionEntity s WHERE s.id = :id")
    Optional<SubmissionEntity> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    List<SubmissionEntity> findByUserIdOrderBySubmittedAtDesc(String userId, Pageable pageable);

    Optional<SubmissionEntity> findByIdAndUserId(UUID id, String userId);

    Optional<SubmissionEntity> findBySimulationId(UUID simulationId);

    List<SubmissionEntity> findByDesignIdAndUserIdOrderBySubmittedAtDesc(UUID designId,
                                                                       String userId,
                                                                       Pageable pageable);

    long countByUserId(String userId);

    /**
     * Submissions the pipeline believes are still running.
     *
     * <p>Used by the reconciler to notice runs that went quiet without a terminal event.
     * Expressed in SQL rather than in Java so it pages rather than loading every submission
     * the service has ever seen.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT s FROM SubmissionEntity s
            WHERE s.status IN (com.archforge.contracts.lifecycle.SubmissionStatus.RECEIVED,
                               com.archforge.contracts.lifecycle.SubmissionStatus.VALIDATING,
                               com.archforge.contracts.lifecycle.SubmissionStatus.COMPILING,
                               com.archforge.contracts.lifecycle.SubmissionStatus.COMPILED,
                               com.archforge.contracts.lifecycle.SubmissionStatus.SIMULATING,
                               com.archforge.contracts.lifecycle.SubmissionStatus.AGGREGATING,
                               com.archforge.contracts.lifecycle.SubmissionStatus.EVALUATING)
            ORDER BY s.submittedAt ASC
            """)
    List<SubmissionEntity> findStalled(Pageable pageable);


}
