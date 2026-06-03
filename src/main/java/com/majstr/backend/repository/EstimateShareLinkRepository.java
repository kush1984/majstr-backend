package com.majstr.backend.repository;

import com.majstr.backend.entity.EstimateShareLink;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
