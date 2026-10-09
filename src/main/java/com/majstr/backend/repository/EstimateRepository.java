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

    /** Whether the object carries a signed document at all — the delete guard (review B-70). */
    boolean existsByProjectIdAndStatus(UUID projectId, EstimateStatus status);

    /**
     * How many of these estimates are not settled yet — the shopping list says out loud that its
     * quantities can still move. A hint on a screen, never a gate.
     */
    long countByIdInAndStatusNot(Collection<UUID> ids, EstimateStatus status);

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

    /**
     * Distinct masters with at least one estimate / one signed estimate (funnel).
     *
     * <p>{@code role = USER} for the same reason as {@code ProjectRepository.countDistinctOwners}:
     * every funnel step counts masters, so an admin with a demo object must not appear in one step
     * and be missing from another.</p>
     */
    @Query("""
            SELECT COUNT(DISTINCT e.project.owner.id) FROM Estimate e
            WHERE e.project.owner.role = com.majstr.backend.entity.Role.USER
            """)
    long countDistinctProjectOwners();

    @Query("""
            SELECT COUNT(DISTINCT e.project.owner.id) FROM Estimate e
            WHERE e.status = :status AND e.project.owner.role = com.majstr.backend.entity.Role.USER
            """)
    long countDistinctProjectOwnersByStatus(@Param("status") EstimateStatus status);

    /** The two estimate funnel steps, grouped by referral source — same rules as the aggregates
     *  above (masters only, distinct owners), so the by-source rows sum to the funnel. */
    @Query("""
            SELECT e.project.owner.referralSource AS source, COUNT(DISTINCT e.project.owner.id) AS cnt
            FROM Estimate e
            WHERE e.project.owner.role = com.majstr.backend.entity.Role.USER
            GROUP BY e.project.owner.referralSource
            """)
    List<com.majstr.backend.dto.SourceCount> countEstimateOwnersBySource();

    @Query("""
            SELECT e.project.owner.referralSource AS source, COUNT(DISTINCT e.project.owner.id) AS cnt
            FROM Estimate e
            WHERE e.status = :status AND e.project.owner.role = com.majstr.backend.entity.Role.USER
            GROUP BY e.project.owner.referralSource
            """)
    List<com.majstr.backend.dto.SourceCount> countOwnersByStatusAndSource(@Param("status") EstimateStatus status);

    /**
     * Signed-estimate owners grouped by first-touch UTM source (V114).
     *
     * <p>{@code utm_source} is NULLABLE and NULL is a real bucket ("arrived with no tags"), so the
     * caller must not use it as a map key unlabelled — it renders as «без UTM».</p>
     */
    @Query("""
            SELECT e.project.owner.utmSource AS source, COUNT(DISTINCT e.project.owner.id) AS cnt
            FROM Estimate e
            WHERE e.status = :status AND e.project.owner.role = com.majstr.backend.entity.Role.USER
            GROUP BY e.project.owner.utmSource
            """)
    List<com.majstr.backend.dto.SourceCount> countOwnersByStatusAndUtmSource(@Param("status") EstimateStatus status);

    /**
     * The headline figure of each given project's card: <b>Σ SIGNED ∧ counted</b> when the object
     * has any, otherwise its latest non-ADDENDUM estimate's status and total. The total sums each
     * line rounded to kopiykas (HALF_UP, matching EstimateService), so subtotals always add up.
     * Projects without an estimate are simply absent from the result. One query for the whole list
     * — no N+1.
     *
     * <p>It used to be «the latest estimate by createdAt, whatever it is» (review B-67), and
     * «whatever it is» became a problem the day work acts started WRITING estimates: signing an act
     * with additional works creates a SIGNED ADDENDUM, which is now the newest row, so an object
     * with an 10 800 ₴ contract and 1 000 ₴ of extras showed «SIGNED · 1 000 ₴» on its card. The
     * object-economy tab meanwhile said 11 800, because it sums {@link #sumIncomeCounted}. Two
     * screens, one object, two contract figures.</p>
     *
     * <p>So the sum branch is deliberately the SAME definition as {@code sumIncomeCounted}
     * (SIGNED ∧ {@code count_in_economy}, ADDENDUMs included — an addendum IS part of the
     * contract), and the fallback exists only for an object with nothing signed yet, where the
     * latest draft is genuinely the best answer to «how big is this job». The ADDENDUM is excluded
     * from the fallback because an object can hold one with no signed parent left counting, and
     * «SIGNED 1 000 ₴» is exactly the misread we are removing.</p>
     *
     * <p>Returns rows of {@code [project_id (UUID), status (String), total (BigDecimal)]}.
     * Postgres-specific (DISTINCT ON); callers must pass a non-empty collection.</p>
     */
    @Query(value = """
            WITH counted AS (
                SELECT e.project_id, SUM(i.line_total) AS total
                FROM estimates e JOIN estimate_items i ON i.estimate_id = e.id
                WHERE e.project_id IN (:projectIds)
                  AND e.status = 'SIGNED' AND e.count_in_economy = true
                GROUP BY e.project_id
            ), latest AS (
                SELECT le.project_id, le.status, COALESCE(SUM(i.line_total), 0) AS total
                FROM (
                    SELECT DISTINCT ON (e.project_id) e.id, e.project_id, e.status
                    FROM estimates e
                    WHERE e.project_id IN (:projectIds) AND e.kind <> 'ADDENDUM'
                    ORDER BY e.project_id, e.created_at DESC, e.id DESC
                ) le
                LEFT JOIN estimate_items i ON i.estimate_id = le.id
                GROUP BY le.project_id, le.status
            )
            SELECT COALESCE(c.project_id, l.project_id) AS project_id,
                   CASE WHEN c.project_id IS NOT NULL THEN 'SIGNED' ELSE l.status END AS status,
                   CASE WHEN c.project_id IS NOT NULL THEN c.total ELSE l.total END AS total
            FROM counted c FULL OUTER JOIN latest l ON l.project_id = c.project_id
            """, nativeQuery = true)
    List<Object[]> findLatestEstimateSummaries(@Param("projectIds") Collection<UUID> projectIds);

    /**
     * Sum of the contract figures of the owner's projects completed since {@code monthStart} —
     * «завершено цього місяця» on the dashboard. Same per-object definition as
     * {@link #findLatestEstimateSummaries} (B-67), so the dashboard cannot disagree with the cards
     * it links to. Completed projects without an estimate contribute 0.
     *
     * <p>Correlated subqueries rather than one grouped pass: a master's completed-this-month list
     * is a handful of objects, and the alternative reads as two joins that happen to mean
     * «unless».</p>
     */
    @Query(value = """
            SELECT COALESCE(SUM(t.total), 0) FROM (
                SELECT COALESCE(
                    (SELECT SUM(i.line_total)
                     FROM estimates e JOIN estimate_items i ON i.estimate_id = e.id
                     WHERE e.project_id = p.id
                       AND e.status = 'SIGNED' AND e.count_in_economy = true),
                    (SELECT COALESCE(SUM(i.line_total), 0)
                     FROM estimate_items i
                     WHERE i.estimate_id = (
                         SELECT e2.id FROM estimates e2
                         WHERE e2.project_id = p.id AND e2.kind <> 'ADDENDUM'
                         ORDER BY e2.created_at DESC, e2.id DESC LIMIT 1)),
                    0) AS total
                FROM projects p
                WHERE p.owner_id = :ownerId
                  AND p.status = 'COMPLETED'
                  AND p.completed_at >= :monthStart
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

    /**
     * «За договором» <b>as it stood at a moment in time</b> — the contract an act that was signed
     * back then was measured against (review B-77).
     *
     * <p>Re-downloading act 3 after act 4 was signed printed act 3's «ДОВІДКОВО» block against
     * today's contract and today's accepted total, so a document the client already holds said
     * something different every time it was rendered. An estimate signed AFTER this act was not
     * part of the deal the act closed.</p>
     *
     * <p>«Counted» is asked AS OF that moment too (review B-94): a parent that a copy superseded
     * LATER still counted when the act was signed, and reading today's {@code count_in_economy}
     * took it out of a document already in the client's hands. The supersede moment is the copy's
     * own signature. What has no timestamp — a manual «виключити з економіки», a consolidation's
     * sources — is still read as it stands today.</p>
     */
    @Query(value = """
            SELECT COALESCE(SUM(i.line_total), 0)
            FROM estimates e JOIN estimate_items i ON i.estimate_id = e.id
            LEFT JOIN estimates successor ON successor.id = e.superseded_by_estimate_id
            WHERE e.project_id = :projectId AND e.status = 'SIGNED'
              AND e.signed_at <= :asOf
              AND (e.count_in_economy = true
                   OR (successor.signed_at IS NOT NULL AND successor.signed_at > :asOf))
            """, nativeQuery = true)
    BigDecimal sumIncomeCountedAsOf(@Param("projectId") UUID projectId,
                                    @Param("asOf") java.time.Instant asOf);

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
     * <p><b>The RATE columns</b> ({@code markup_rate}/{@code discount_rate}) carry the percent each
     * adjustment was actually written at, and NULL unless there is EXACTLY ONE «% від кошторису»
     * line of that direction and no FROZEN one — one figure would otherwise be a number nobody's
     * estimate carries. That is the portal's rule verbatim (review B-75): this query used to accept
     * several lines as long as they happened to share a rate, and to read a rate off a frozen
     * consolidated line, whose stored percent was measured against a sum that is not on this sheet.
     * The master's panel and the client's portal print the SAME adjustment, so they may not disagree
     * about whether it has a rate. They are sent rather than derived
     * on the client because such a line is measured against its OWN TYPE's subtotal: dividing the
     * amount by works+materials is what made the panel print «14,776%» for a discount the master had
     * typed as 15. (Note for whoever edits the SQL below: an apostrophe inside a {@code --} comment
     * there is read as the start of a string literal and the query fails to parse at startup.)</p>
     *
     * <p>Row shape: {@code [id (UUID), name (String), count_in_economy (Boolean),
     * signed_at (Timestamp), works (BigDecimal), materials (BigDecimal), markup (BigDecimal),
     * discount (BigDecimal), markup_rate (BigDecimal, nullable), discount_rate (BigDecimal,
     * nullable), kind (String)]}.</p>
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
                   CASE WHEN COUNT(CASE WHEN i.unit = 'PERCENT' AND i.line_total > 0
                                          AND i.percent_base_kind = 'TOTAL'
                                     THEN 1 END) = 1
                             AND COUNT(CASE WHEN i.unit = 'PERCENT' AND i.line_total > 0
                                              AND i.base_origin_label IS NOT NULL
                                         THEN 1 END) = 0
                        THEN MIN(CASE WHEN i.unit = 'PERCENT' AND i.line_total > 0
                                        AND i.percent_base_kind = 'TOTAL'
                                   THEN i.quantity END) END AS markup_rate,
                   CASE WHEN COUNT(CASE WHEN i.unit = 'PERCENT' AND i.line_total < 0
                                          AND i.percent_base_kind = 'TOTAL'
                                     THEN 1 END) = 1
                             AND COUNT(CASE WHEN i.unit = 'PERCENT' AND i.line_total < 0
                                              AND i.base_origin_label IS NOT NULL
                                         THEN 1 END) = 0
                        THEN MIN(CASE WHEN i.unit = 'PERCENT' AND i.line_total < 0
                                        AND i.percent_base_kind = 'TOTAL'
                                   THEN i.quantity END) END AS discount_rate,
                   e.kind
            FROM estimates e
            LEFT JOIN estimate_items i ON i.estimate_id = e.id
            WHERE e.project_id = :projectId AND e.status = 'SIGNED'
            GROUP BY e.id, e.name, e.count_in_economy, e.signed_at, e.kind
            ORDER BY e.signed_at ASC
            """, nativeQuery = true)
    List<Object[]> findSignedEstimateSummaries(@Param("projectId") UUID projectId);

    /** The object's SIGNED copies made with a MARKUP — the only estimates that can report a crew
     *  margin. A DISCOUNT duplicate is excluded at the query, not later: it is a cheaper offer to
     *  the client, not a crew sheet.
     *
     *  <p>{@code count_in_economy} is part of the condition (review B-75): a copy SUPERSEDED by a
     *  later renegotiation is SIGNED forever, and its panel went on reporting «Твоя націнка» on a
     *  deal that no longer counts anywhere else on the tab — a margin the master will never see.</p> */
    @Query("""
            SELECT e FROM Estimate e
            WHERE e.project.id = :projectId
              AND e.status = com.majstr.backend.entity.EstimateStatus.SIGNED
              AND e.countInEconomy = true
              AND e.crewPriced = true
            """)
    List<Estimate> findSignedMarkupDuplicates(@Param("projectId") UUID projectId);

    /**
     * Masters who have ever priced a client's sheet ABOVE a crew's — the only footprint a бригадир
     * leaves in this product. Counts owners, not estimates, so a master with five copies is one.
     *
     * <p>{@code role = USER} for the same reason every funnel step filters it: an admin's demo data
     * must not appear in one report and be missing from the one beside it.</p>
     *
     * @param since   count only copies created after this instant. Never null — {@code Instant.EPOCH}
     *                means «all time». A nullable parameter here made Postgres refuse the query
     *                outright («could not determine data type of parameter»), and a sentinel reads
     *                better than a cast anyway.
     * @param signed  when true, only copies the client actually signed
     */
    @Query("""
            SELECT COUNT(DISTINCT e.project.owner.id) FROM Estimate e
            WHERE e.project.owner.role = com.majstr.backend.entity.Role.USER
              AND e.markupPercent > 0
              AND e.createdAt >= :since
              AND (:signed = false OR e.status = com.majstr.backend.entity.EstimateStatus.SIGNED)
            """)
    long countMastersWithMarkupCopy(@Param("since") Instant since, @Param("signed") boolean signed);
}
