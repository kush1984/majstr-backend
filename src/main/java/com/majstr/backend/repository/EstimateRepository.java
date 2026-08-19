package com.majstr.backend.repository;

import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface EstimateRepository extends JpaRepository<Estimate, UUID> {

    List<Estimate> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    /** Every client-price copy made from this estimate — read when the parent's lines are deleted,
     *  so the same positions go from the copies too. */
    List<Estimate> findByDuplicatedFromId(UUID duplicatedFromId);

    /** Portal sections, oldest first — so «Кошторис 1» stays first as new ones are added. */
    List<Estimate> findByProjectIdAndPortalVisibleTrueOrderByCreatedAtAsc(UUID projectId);

    /** Economy portal acts, oldest first — same ordering rule as the SIGNATURE portal. */
    List<Estimate> findByProjectIdAndEconomyVisibleTrueOrderByCreatedAtAsc(UUID projectId);

    /** All estimates of a project, any status — the live count for the FREE
     *  per-project estimate limit (deleting one frees a slot). */
    long countByProjectId(UUID projectId);

    // ---- admin activity ---------------------------------------------------

    /** Estimate count per owner for a set of users (admin list, no N+1). */
    @Query("SELECT e.project.owner.id AS ownerId, COUNT(e) AS cnt FROM Estimate e "
            + "WHERE e.project.owner.id IN :ownerIds GROUP BY e.project.owner.id")
    List<OwnerCount> countByProjectOwnerIdIn(@Param("ownerIds") Collection<UUID> ownerIds);

    /** Estimate count per owner filtered by status (e.g. SIGNED) for the admin list. */
    @Query("SELECT e.project.owner.id AS ownerId, COUNT(e) AS cnt FROM Estimate e "
            + "WHERE e.project.owner.id IN :ownerIds AND e.status = :status GROUP BY e.project.owner.id")
    List<OwnerCount> countByProjectOwnerIdInAndStatus(@Param("ownerIds") Collection<UUID> ownerIds,
                                                      @Param("status") EstimateStatus status);

    /** Per-status estimate counts for one owner (admin user detail). Rows: [status, count]. */
    @Query("SELECT e.status, COUNT(e) FROM Estimate e WHERE e.project.owner.id = :ownerId GROUP BY e.status")
    List<Object[]> countByStatusForOwner(@Param("ownerId") UUID ownerId);

    /** When the owner last created an estimate (admin user detail; null if none). */
    @Query("SELECT MAX(e.createdAt) FROM Estimate e WHERE e.project.owner.id = :ownerId")
    Instant findLastEstimateCreatedAt(@Param("ownerId") UUID ownerId);

    /** Distinct masters with at least one estimate / one signed estimate (funnel). */
    @Query("SELECT COUNT(DISTINCT e.project.owner.id) FROM Estimate e")
    long countDistinctProjectOwners();

    @Query("SELECT COUNT(DISTINCT e.project.owner.id) FROM Estimate e WHERE e.status = :status")
    long countDistinctProjectOwnersByStatus(@Param("status") EstimateStatus status);

    /**
     * For each given project, the latest estimate (by createdAt) with its status
     * and total. The total sums each line rounded to kopiykas (HALF_UP, matching
     * EstimateService), so subtotals always add up. Projects without an estimate
     * are simply absent from the result. One query for the whole list — no N+1.
     *
     * <p>Returns rows of {@code [project_id (UUID), status (String), total (BigDecimal)]}.
     * Postgres-specific (DISTINCT ON); callers must pass a non-empty collection.</p>
     */
    @Query(value = """
            SELECT le.project_id, le.status,
                   COALESCE(SUM(i.line_total), 0) AS total
            FROM (
                SELECT DISTINCT ON (e.project_id) e.id, e.project_id, e.status
                FROM estimates e
                WHERE e.project_id IN (:projectIds)
                ORDER BY e.project_id, e.created_at DESC, e.id DESC
            ) le
            LEFT JOIN estimate_items i ON i.estimate_id = le.id
            GROUP BY le.project_id, le.status
            """, nativeQuery = true)
    List<Object[]> findLatestEstimateSummaries(@Param("projectIds") Collection<UUID> projectIds);

    /**
     * Sum of the latest-estimate totals of the owner's projects completed since
     * {@code monthStart}. Completed projects without an estimate contribute 0.
     */
    @Query(value = """
            SELECT COALESCE(SUM(t.total), 0) FROM (
                SELECT le.project_id,
                       COALESCE(SUM(i.line_total), 0) AS total
                FROM (
                    SELECT DISTINCT ON (e.project_id) e.id, e.project_id
                    FROM estimates e
                    JOIN projects p ON p.id = e.project_id
                    WHERE p.owner_id = :ownerId
                      AND p.status = 'COMPLETED'
                      AND p.completed_at >= :monthStart
                    ORDER BY e.project_id, e.created_at DESC, e.id DESC
                ) le
                LEFT JOIN estimate_items i ON i.estimate_id = le.id
                GROUP BY le.project_id
            ) t
            """, nativeQuery = true)
    BigDecimal sumLatestEstimateTotalForCompletedSince(@Param("ownerId") UUID ownerId,
                                                       @Param("monthStart") Instant monthStart);

    /**
     * Income for one object (project) — the sum of ALL its estimates' line totals
     * EXCEPT rejected ones. Line totals are rounded per line (HALF_UP, matching
     * EstimateService), so the numbers agree with the estimate view. Drives the
     * object-economy summary; one aggregate query, no N+1.
     */
    @Query(value = """
            SELECT COALESCE(SUM(i.line_total), 0)
            FROM estimates e JOIN estimate_items i ON i.estimate_id = e.id
            WHERE e.project_id = :projectId AND e.status <> 'REJECTED'
            """, nativeQuery = true)
    BigDecimal sumIncomeExcludingRejected(@Param("projectId") UUID projectId);

    /** Same, restricted to SIGNED estimates — the "of which signed" figure. */
    @Query(value = """
            SELECT COALESCE(SUM(i.line_total), 0)
            FROM estimates e JOIN estimate_items i ON i.estimate_id = e.id
            WHERE e.project_id = :projectId AND e.status = 'SIGNED'
            """, nativeQuery = true)
    BigDecimal sumIncomeSigned(@Param("projectId") UUID projectId);

    /** Object income (the "За договором" / contracted figure) = the sum of line totals of
     *  {@code SIGNED} estimates FLAGGED to count in the economy — replaces the sum-of-all figure.
     *
     *  <p>REJECTED is excluded regardless of the flag: a rejected estimate is a deal the
     *  client turned down, so it is never income. The flag alone was not enough — V57
     *  blanket-set it TRUE on every existing estimate, which silently counted rejected
     *  variants as earnings until the owner unticked them by hand (V67 patches the data;
     *  this guard makes it impossible to re-introduce).</p>
     *
     *  <p><b>SIGNED is likewise not optional</b> (economy-contracted-signed-only-fix): {@code
     *  count_in_economy} defaults {@code true} even on a fresh DRAFT/SENT estimate — it means
     *  "counts if it becomes the deal," not "is the deal." Without this filter, contracted summed
     *  every counted DRAFT/SENT alongside the actually-signed ones, disagreeing with the act
     *  panels below ({@link #findSignedEstimateSummaries}, which was already {@code SIGNED}-only)
     *  — an object with nothing signed yet showed money in Платежі with an empty acts list.</p> */
    @Query(value = """
            SELECT COALESCE(SUM(i.line_total), 0)
            FROM estimates e JOIN estimate_items i ON i.estimate_id = e.id
            WHERE e.project_id = :projectId AND e.count_in_economy = true AND e.status = 'SIGNED'
            """, nativeQuery = true)
    BigDecimal sumIncomeCounted(@Param("projectId") UUID projectId);

    /** Sum of deposits (завдаток) across the object's counted SIGNED estimates — the
     *  "received from client" cash-flow figure. Legacy: superseded by {@code
     *  PaymentReceiptRepository.sumByProjectId} (payments-economy-portal iteration, then V100's
     *  PLAN/FACT split); kept only because it still reads live {@code deposit_amount} data on
     *  estimates predating the migration that nothing writes to anymore. <b>Dead code today —
     *  zero callers</b> (grepped main); fixed alongside {@link #sumIncomeCounted} for consistency
     *  (same missing-{@code SIGNED} bug applied here too) rather than left as a landmine for
     *  whoever revives it. */
    @Query("""
            SELECT COALESCE(SUM(e.depositAmount), 0)
            FROM Estimate e
            WHERE e.project.id = :projectId AND e.countInEconomy = true
                  AND e.status = com.majstr.backend.entity.EstimateStatus.SIGNED
                  AND e.depositAmount IS NOT NULL
            """)
    BigDecimal sumDepositsCounted(@Param("projectId") UUID projectId);

    /**
     * Per-SIGNED-estimate works/materials/markup/discount totals, for the object economy's
     * per-estimate panels. Every SIGNED estimate gets a row regardless of {@code count_in_economy}
     * (the master sees every "act" he signed) — {@code count_in_economy} rides along so the caller
     * can flag a panel whose amount is NOT folded into the counted-only summary total, rather than
     * let the two numbers silently disagree.
     *
     * <p>works/materials are the <b>pre-adjustment (gross)</b> per-type subtotals — a «TOTAL»
     * percent line (or a frozen consolidated one) is excluded from its type's sum here and reported
     * separately via markup/discount instead, the same math {@code TypeBreakdown} (PWA) and
     * {@code typeBase()} (portal) already use for a single estimate's own view. Folding the
     * adjustment INTO works/materials here (the pre-2026-08-14 shape) made an estimate's panel on
     * this tab disagree with what it shows when opened directly — «Роботи» read as the already-
     * discounted figure with no visible «before» to compare the Знижка recap against. The caller
     * reconstitutes the actual signed total as {@code works + materials + markup + discount}
     * (discount is negative), not {@code works + materials} — see {@link
     * com.majstr.backend.service.ObjectExpenseService#signedEstimatePanels}.</p>
     *
     * <p>Row shape: {@code [id (UUID), name (String), count_in_economy (Boolean),
     * signed_at (Timestamp), works (BigDecimal), materials (BigDecimal), markup (BigDecimal),
     * discount (BigDecimal)]}.</p>
     */
    @Query(value = """
            SELECT e.id, e.name, e.count_in_economy, e.signed_at,
                   COALESCE(SUM(CASE WHEN i.type = 'WORK'
                                       AND NOT (i.unit = 'PERCENT'
                                                AND (i.percent_base_kind = 'TOTAL' OR i.base_origin_label IS NOT NULL))
                                  THEN i.line_total ELSE 0 END), 0) AS works,
                   COALESCE(SUM(CASE WHEN i.type = 'MATERIAL'
                                       AND NOT (i.unit = 'PERCENT'
                                                AND (i.percent_base_kind = 'TOTAL' OR i.base_origin_label IS NOT NULL))
                                  THEN i.line_total ELSE 0 END), 0) AS materials,
                   COALESCE(SUM(CASE WHEN i.unit = 'PERCENT' AND i.line_total > 0
                                       AND (i.percent_base_kind = 'TOTAL' OR i.base_origin_label IS NOT NULL)
                                  THEN i.line_total ELSE 0 END), 0) AS markup,
                   COALESCE(SUM(CASE WHEN i.unit = 'PERCENT' AND i.line_total < 0
                                       AND (i.percent_base_kind = 'TOTAL' OR i.base_origin_label IS NOT NULL)
                                  THEN i.line_total ELSE 0 END), 0) AS discount,
                   e.kind
            FROM estimates e
            LEFT JOIN estimate_items i ON i.estimate_id = e.id
            WHERE e.project_id = :projectId AND e.status = 'SIGNED'
            GROUP BY e.id, e.name, e.count_in_economy, e.signed_at, e.kind
            ORDER BY e.signed_at ASC
            """, nativeQuery = true)
    List<Object[]> findSignedEstimateSummaries(@Param("projectId") UUID projectId);
}
