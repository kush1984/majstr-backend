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
     * <p>The {@code estimate_id IS NULL} branch is mandatory, not belt-and-braces: ADDITIONAL
     * (off-estimate) lines are stored with {@code estimate_id = null}, and their rolled-up ADDENDUM
     * estimate ({@code count_in_economy = true}) is part of «За договором» — so they must count on this
     * side too, or the two axes would drift the other way.</p>
     */
    @Query(value = """
            SELECT COALESCE(SUM(wai.line_total), 0)
            FROM work_act_item wai
            JOIN work_act wa ON wa.id = wai.work_act_id
            LEFT JOIN estimates e ON e.id = wai.estimate_id
            WHERE wa.project_id = :projectId
              AND wa.status = 'SIGNED'
              AND (wai.estimate_id IS NULL OR e.count_in_economy = true)
            """, nativeQuery = true)
    BigDecimal sumSignedActLineTotals(@Param("projectId") UUID projectId);
}
