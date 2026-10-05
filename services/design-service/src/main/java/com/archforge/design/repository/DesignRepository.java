package com.archforge.design.repository;

import com.archforge.design.domain.DesignEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Designs, always filtered by owner.
 *
 * <p>There is deliberately no {@code findById(UUID)} on the interface. A design id is a
 * bearer reference to somebody's work, so an unfiltered lookup by id is the exact shape of
 * bug that turns an id enumeration into a data leak. Every read here takes a user id, which
 * makes "did I remember to check?" impossible to express.
 */
public interface DesignRepository extends JpaRepository<DesignEntity, UUID> {

    List<DesignEntity> findByUserIdOrderByUpdatedAtDesc(String userId, Pageable pageable);

    Optional<DesignEntity> findByIdAndUserId(UUID id, String userId);

    long countByUserId(String userId);
}