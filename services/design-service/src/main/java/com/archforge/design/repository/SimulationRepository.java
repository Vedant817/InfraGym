package com.archforge.design.repository;

import com.archforge.design.domain.SimulationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Simulation runs.
 *
 * <p>Not owner-filtered at the repository, unlike designs and submissions, because the
 * simulation table is reached through a submission that has already been ownership-checked.
 * That is a deliberate asymmetry rather than an oversight: adding {@code userId} to a table
 * that does not have the column would mean denormalising an owner onto it for the sake of
 * symmetry, and the check that matters happens one join earlier.
 */
public interface SimulationRepository extends JpaRepository<SimulationEntity, UUID> {

    Optional<SimulationEntity> findBySubmissionId(UUID submissionId);

    List<SimulationEntity> findBySubmissionIdInOrderByCompletedAtDesc(List<UUID> submissionIds);
}