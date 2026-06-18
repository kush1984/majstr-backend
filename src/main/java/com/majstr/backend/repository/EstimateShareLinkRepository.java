package com.majstr.backend.repository;

import com.majstr.backend.entity.EstimateShareLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EstimateShareLinkRepository extends JpaRepository<EstimateShareLink, UUID> {

    Optional<EstimateShareLink> findByToken(String token);

    List<EstimateShareLink> findByEstimateIdOrderByCreatedAtDesc(UUID estimateId);

    /** Most recent non-revoked link for an estimate — reused by the email-share flow. */
    Optional<EstimateShareLink> findFirstByEstimateIdAndRevokedFalseOrderByCreatedAtDesc(UUID estimateId);

    // ---- admin activity ---------------------------------------------------

    /** Share links this master ever created (admin user detail: > 0 → has shared). */
    @Query("SELECT COUNT(l) FROM EstimateShareLink l WHERE l.estimate.project.owner.id = :ownerId")
    long countByOwner(@Param("ownerId") UUID ownerId);

    /** Distinct masters who shared at least one estimate (funnel step). */
    @Query("SELECT COUNT(DISTINCT l.estimate.project.owner.id) FROM EstimateShareLink l")
    long countDistinctOwners();
}
