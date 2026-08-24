package com.majstr.backend.repository;

import com.majstr.backend.dto.OwnerSource;
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

    /**
     * Masters who ever minted a per-estimate ({@code ?t=}) link — ONE HALF of the funnel's
     * {@code shared} step; the other half is {@link ProjectShareLinkRepository#findSharedOwners}.
     *
     * <p>Ids, not a COUNT, because the two halves overlap heavily (most masters have both kinds of
     * link) and summing two counts would count those masters twice. The caller unions the sets.</p>
     *
     * <p>The referral source rides along so the by-source breakdown and the aggregate step are the
     * same computation — see {@link com.majstr.backend.dto.OwnerSource}.</p>
     *
     * <p><b>No {@code revoked}/{@code expiresAt} filter, on purpose.</b> The step means "ever shared",
     * so a master who later revoked the link still passed it. Filtering would make a funnel step
     * shrink over time, which a funnel step must never do.</p>
     */
    @Query("""
            SELECT DISTINCT l.estimate.project.owner.id AS ownerId,
                            l.estimate.project.owner.referralSource AS source
            FROM EstimateShareLink l
            WHERE l.estimate.project.owner.role = com.majstr.backend.entity.Role.USER
            """)
    List<OwnerSource> findSharedOwners();
}
