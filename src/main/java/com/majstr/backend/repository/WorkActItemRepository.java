package com.majstr.backend.repository;

import com.majstr.backend.entity.WorkActItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface WorkActItemRepository extends JpaRepository<WorkActItem, UUID> {

    List<WorkActItem> findByWorkActIdOrderBySortOrderAscIdAsc(UUID workActId);

    /** Wholesale-replace helper: the PUT /items path clears and re-inserts the act's lines. */
    void deleteByWorkActId(UUID workActId);

    /** Empty-act guard (review fix): an act with no lines can be neither shared nor signed. */
    boolean existsByWorkActId(UUID workActId);

    /**
     * Whether any SIGNED act holds a line frozen from this estimate — the guard that blocks
     * {@code EstimateService#reopen} (review fix): reopening (and then editing or deleting) an
     * estimate that SIGNED acts closed lines against removes it from «За договором» while the act
     * lines keep counting in «Прийнято актами» — the exact drift the acts-fix eliminated. Checked
     * via the line's frozen {@code estimateId} (survives item edits), SIGNED acts only — an open
     * DRAFT/SENT act is still editable, so it can absorb the change.
     */
    @Query("""
            SELECT COUNT(wai) > 0
            FROM WorkActItem wai
            WHERE wai.estimateId = :estimateId
              AND wai.workAct.status = com.majstr.backend.entity.WorkActStatus.SIGNED
            """)
    boolean existsSignedLineForEstimate(@Param("estimateId") UUID estimateId);

    /**
     * The same question asked of every act that is still alive — DRAFT and SENT included (B-59).
     * A DRAFT act can absorb an edit to the estimate, but it cannot absorb the estimate LEAVING
     * «За договором»: reopening, deleting or unticking it while a SENT act sits on the client's
     * phone waiting to be signed means the signature lands on lines whose estimate is no longer
     * counted, and «Прийнято актами» outgrows «За договором» the moment he taps. REJECTED acts are
     * dead paper and hold nothing back.
     */
    @Query("""
            SELECT COUNT(wai) > 0
            FROM WorkActItem wai
            WHERE wai.estimateId = :estimateId
              AND wai.workAct.status <> com.majstr.backend.entity.WorkActStatus.REJECTED
            """)
    boolean existsLiveActLineForEstimate(@Param("estimateId") UUID estimateId);

    /**
     * How much of each estimate line has been closed by SIGNED acts of an object — the source of
     * both the progress endpoint's «виконано з початку» and the {@code cumulative_before} a new act
     * freezes. Rows: {@code [estimate_item_id (UUID), done (BigDecimal)]}. Additional (estimate_item_id
     * NULL) lines are excluded — they close nothing measurable. Native so one aggregate serves the
     * whole object, no N+1.
     */
    @Query(value = """
            SELECT wai.estimate_item_id, COALESCE(SUM(wai.quantity), 0) AS done
            FROM work_act_item wai
            JOIN work_act wa ON wa.id = wai.work_act_id
            WHERE wa.project_id = :projectId
              AND wa.status = 'SIGNED'
              AND wai.estimate_item_id IS NOT NULL
            GROUP BY wai.estimate_item_id
            """, nativeQuery = true)
    List<Object[]> sumSignedQuantitiesByEstimateItem(@Param("projectId") UUID projectId);

    /**
     * Σ line totals over the object's SIGNED acts («Прийнято актами») — the value of work the client
     * has accepted, counted over the SAME set of estimates as «За договором» ({@code sumIncomeCounted}
     * — SIGNED and {@code count_in_economy = true}). A line whose estimate is EXCLUDED from the economy
     * must NOT count here, or the numerator would outgrow its denominator and the percentage could
     * pass 100% (acts-fix).
     *
     * <p>The ADDITIONAL branch is mandatory, not belt-and-braces: off-estimate lines have no
     * estimate of their own, and their rolled-up ADDENDUM ({@code count_in_economy = true}) is part
     * of «За договором» — so they must count on this side too, or the two axes would drift the
     * other way. It asks {@code line_kind} rather than {@code estimate_id IS NULL} (B-55): an
     * ADJUSTMENT — an estimate's own discount prorated onto this act — carries no item id either,
     * and it belongs to its estimate's branch, not to the unconditional one.</p>
     *
     * <p><b>{@code e.status = 'SIGNED'} is the other half</b> (B-59): «За договором» counts SIGNED
     * estimates only, so a reopened one leaves the denominator instantly. Without this its act
     * lines kept counting here, and «Прийнято актами» stood at 10 000 against a contract of 0.</p>
     */
    @Query(value = """
            SELECT COALESCE(SUM(wai.line_total), 0)
            FROM work_act_item wai
            JOIN work_act wa ON wa.id = wai.work_act_id
            LEFT JOIN estimates e ON e.id = wai.estimate_id
            WHERE wa.project_id = :projectId
              AND wa.status = 'SIGNED'
              AND (wai.line_kind = 'ADDITIONAL'
                   OR (e.status = 'SIGNED' AND e.count_in_economy = true))
            """, nativeQuery = true)
    BigDecimal sumSignedActLineTotals(@Param("projectId") UUID projectId);

    /**
     * The бригадир's margin the client has already ACCEPTED, per marked-up copy:
     * {@code Σ (act price − crew price) × act quantity} over SIGNED acts.
     *
     * <p><b>The set is deliberately NARROWER than «Прийнято актами»</b>
     * ({@link #sumSignedActLineTotals}), and the difference is not an oversight — it is forced.
     * That query counts an off-estimate ADDITIONAL line ({@code estimate_id IS NULL}) because its
     * rolled-up ADDENDUM is part of «За договором»; here such a line cannot be counted at all,
     * because it was never a copy line and no crew price for it exists anywhere. Same for a line
     * whose {@code source_unit_price} is NULL — added to the copy afterwards, and we do not know
     * whether the crew is paid for it, so it contributes zero rather than the whole amount.</p>
     *
     * <p>PERCENT lines need no exclusion clause: the progress picker skips them («a «%» line has no
     * quantity to close») and, since B-57, {@code ActLineBinder} refuses one outright — no act line
     * can carry the unit at all.</p>
     *
     * <p>{@code wai.unit_price} is read rather than {@code ei.unit_price} only because the act line
     * is the FROZEN copy: since B-56 a linked line takes its price FROM the estimate item at write
     * time, so the two agree at the moment of signing, and the act's copy is the one that keeps
     * agreeing after the estimate is edited. The margin follows the money actually accepted.</p>
     */
    @Query(value = """
            SELECT COALESCE(SUM((wai.unit_price - ei.source_unit_price) * wai.quantity), 0)
            FROM work_act_item wai
            JOIN work_act wa ON wa.id = wai.work_act_id
            JOIN estimate_items ei ON ei.id = wai.estimate_item_id
            WHERE wa.status = 'SIGNED'
              AND ei.estimate_id = :estimateId
              AND ei.source_unit_price IS NOT NULL
            """, nativeQuery = true)
    BigDecimal sumSignedActMargin(@Param("estimateId") UUID estimateId);
}
