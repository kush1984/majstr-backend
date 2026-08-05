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

    /** All estimates of a project, any status — the live count for the FREE
     *  per-project estimate limit (deleting one frees a slot). */
    long countByProjectId(UUID projectId);

    long countByProjectOwnerIdAndStatus(UUID ownerId, EstimateStatus status);

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

    /** Object income = the sum of line totals of estimates FLAGGED to count in the
     *  economy (the accepted deal(s)) — replaces the sum-of-all figure.
     *
     *  <p>REJECTED is excluded regardless of the flag: a rejected estimate is a deal the
     *  client turned down, so it is never income. The flag alone was not enough — V57
     *  blanket-set it TRUE on every existing estimate, which silently counted rejected
     *  variants as earnings until the owner unticked them by hand (V67 patches the data;
     *  this guard makes it impossible to re-introduce).</p> */
    @Query(value = """
            SELECT COALESCE(SUM(i.line_total), 0)
            FROM estimates e JOIN estimate_items i ON i.estimate_id = e.id
            WHERE e.project_id = :projectId AND e.count_in_economy = true AND e.status <> 'REJECTED'
            """, nativeQuery = true)
    BigDecimal sumIncomeCounted(@Param("projectId") UUID projectId);

    /**
     * Works (labour) subtotal of the counted estimates — the master's earnings base.
     *
     * <p><b>A duplicate earns only its markup.</b> When an estimate was copied from another with a
     * markup (the бригадир's two-price workflow), the lower price is what he PAYS his crew and only
     * the difference is his. Counting the client-facing total would report his crew's wages as his
     * income — on a 100 000 ₴ object at +15 % that is 100 000 ₴ of imaginary earnings instead of
     * 15 000 ₴ of real ones.</p>
     *
     * <p>The subtraction is per LINE, against the price stored on that line when it was copied, and
     * NOT against the parent estimate: the parent may have been deleted, half its lines may have
     * been marked up while the rest passed through at cost, and either sheet may have been edited
     * since. {@code COALESCE(source_unit_price, 0)} is what makes a line ADDED to the duplicate
     * afterwards count in full — nobody downstream is paid for it, so all of it is margin.</p>
     *
     * <p><b>A percentage line earns too, and reaching its base is the only way to see it.</b> Two
     * shapes, both real. Base is a MATERIAL (never marked up): the copy carries a raised PERCENT —
     * 20 % becomes 26 % at +30 % — so 5 000 ₴ of шафа yields 1 300 against the parent's 1 000, and
     * the margin is 300. Base is a WORK: the percent is left alone because the base itself grew, so
     * 20 % of 1 300 is 260 against the parent's 200, and the margin is 60. Subtracting per unit
     * price would report ZERO in the second case and nothing sensible in the first — a percentage
     * has no price to subtract. Hence {@code source_unit_price} holding the ORIGINAL PERCENT on
     * these rows, and the LEFT JOIN that finds what the crew's sheet charged for the base.</p>
     */
    @Query(value = """
            SELECT COALESCE(SUM(
                CASE
                    -- An ordinary estimate: the master keeps what the line charges.
                    WHEN e.duplicated_from_id IS NULL THEN i.line_total
                    -- A duplicate, ordinary line: the difference between the two prices.
                    WHEN i.unit <> 'PERCENT'
                        THEN ROUND(i.quantity * (i.unit_price - COALESCE(i.source_unit_price, 0)), 2)
                    -- A duplicate, percentage line: what it charges minus what the crew's sheet
                    -- charged for it. source_unit_price holds the ORIGINAL PERCENT here, so the
                    -- crew's amount is that percent of the crew's own base — which is why the base
                    -- has to be reached rather than guessed.
                    ELSE i.line_total - ROUND(COALESCE(i.source_unit_price, 0) / 100 * COALESCE(
                        CASE i.percent_base_kind
                            -- A hand-typed sum is not marked up, so both sheets share it.
                            WHEN 'MANUAL' THEN i.unit_price
                            -- The base line carries its own crew price.
                            WHEN 'POSITION'
                                THEN ROUND(b.quantity * COALESCE(b.source_unit_price, b.unit_price), 2)
                            -- Measured against the crew's ordinary subtotal. Deliberately ordinary
                            -- lines only: a percentage of the whole sheet is an overhead on the
                            -- work, and folding other percentages into its base would make two
                            -- TOTAL lines depend on each other — the thing EstimateMath refuses to
                            -- do when it computes them all against one base.
                            WHEN 'TOTAL' THEN (
                                SELECT COALESCE(SUM(ROUND(o.quantity
                                        * COALESCE(o.source_unit_price, o.unit_price), 2)), 0)
                                FROM estimate_items o
                                WHERE o.estimate_id = i.estimate_id AND o.unit <> 'PERCENT')
                        END, 0), 2)
                END), 0)
            FROM estimates e
            JOIN estimate_items i ON i.estimate_id = e.id
            LEFT JOIN estimate_items b ON b.id = i.percent_base_item_id
            WHERE e.project_id = :projectId AND e.count_in_economy = true AND e.status <> 'REJECTED' AND i.type = 'WORK'
            """, nativeQuery = true)
    BigDecimal sumWorksCounted(@Param("projectId") UUID projectId);

    /** Materials subtotal of the counted estimates — passthrough, not earnings (reference). */
    @Query(value = """
            SELECT COALESCE(SUM(i.line_total), 0)
            FROM estimates e JOIN estimate_items i ON i.estimate_id = e.id
            WHERE e.project_id = :projectId AND e.count_in_economy = true AND e.status <> 'REJECTED' AND i.type = 'MATERIAL'
            """, nativeQuery = true)
    BigDecimal sumMaterialsCounted(@Param("projectId") UUID projectId);

    /** Sum of deposits (завдаток) across the object's counted estimates — the
     *  "received from client" cash-flow figure. */
    @Query("""
            SELECT COALESCE(SUM(e.depositAmount), 0)
            FROM Estimate e
            WHERE e.project.id = :projectId AND e.countInEconomy = true AND e.depositAmount IS NOT NULL
            """)
    BigDecimal sumDepositsCounted(@Param("projectId") UUID projectId);
}
