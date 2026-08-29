package com.majstr.backend.repository;

import com.majstr.backend.entity.WorkActReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkActReceiptRepository extends JpaRepository<WorkActReceipt, UUID> {

    /**
     * The act's receipts in the ONE order they are shown in — newest paper first, undated on top
     * (receipts-batch iteration, master's request). Every reader goes through this: the editor
     * list, the PDF, the client portal and the ADDENDUM rollup, so the four can never disagree
     * about which receipt is «1.».
     *
     * <p>Undated first is deliberate: a receipt with no date is one the master still has to look
     * at, so it belongs where he lands. {@code sortOrder} only breaks ties — it is insertion order,
     * which is what the whole list used to be sorted by.</p>
     */
    @Query("""
            SELECT r FROM WorkActReceipt r
            WHERE r.workAct.id = :actId
            ORDER BY r.issuedAt DESC NULLS FIRST, r.sortOrder ASC, r.createdAt ASC
            """)
    List<WorkActReceipt> findByWorkActIdNewestFirst(@Param("actId") UUID actId);

    Optional<WorkActReceipt> findByIdAndWorkActId(UUID id, UUID workActId);

    /** Content check for the empty-act guard: receipts make an act signable too (round 2). */
    boolean existsByWorkActId(UUID workActId);

    long countByWorkActId(UUID workActId);

    /**
     * Is any receipt still unpriced? Since the receipts-batch iteration a photographed receipt is
     * saved BEFORE it is read, so amount 0 is a normal intermediate state — and this is what stops
     * it reaching a document: see {@link com.majstr.backend.service.ActReceiptCompleteness}.
     */
    @Query("""
            SELECT COUNT(r) > 0 FROM WorkActReceipt r
            WHERE r.workAct.id = :actId AND r.amount <= 0
            """)
    boolean existsUnpricedByWorkActId(@Param("actId") UUID actId);

    @Query("SELECT COALESCE(MAX(r.sortOrder), -1) FROM WorkActReceipt r WHERE r.workAct.id = :actId")
    int maxSortOrder(@Param("actId") UUID actId);

    /** This act's BILLED receipts — paid less returned (V115), itemized ones excluded (their
     *  positions already carry the money as act lines, round 2). Feeds the «ДОВІДКОВО» accepted
     *  figure for an unsigned act. */
    @Query("""
            SELECT COALESCE(SUM(r.amount - r.returnedAmount), 0) FROM WorkActReceipt r
            WHERE r.workAct.id = :actId AND r.itemized = false
            """)
    BigDecimal sumByWorkActId(@Param("actId") UUID actId);

    /**
     * Σ receipt amounts over the object's SIGNED acts — the receipts half of «Прийнято актами».
     *
     * <p>Nets the partial returns (V115) for the same reason the ADDENDUM does: material handed
     * back to the shop was never accepted by anybody, and the two figures are compared.</p>
     *
     * <p><b>Pairs with {@code WorkActItemRepository.sumSignedActLineTotals}: both must be added
     * wherever «Прийнято актами» is computed.</b> Signing rolls the receipts into the act's SIGNED
     * ADDENDUM estimate ({@code count_in_economy = true}), so they are inside «За договором»; leaving
     * them out here would make the numerator smaller than the money the client actually owes and the
     * works axis would read a phantom «Невідпрацьований аванс» the moment the act is paid.</p>
     */
    @Query(value = """
            SELECT COALESCE(SUM(r.amount - r.returned_amount), 0)
            FROM work_act_receipt r
            JOIN work_act wa ON wa.id = r.work_act_id
            WHERE wa.project_id = :projectId
              AND wa.status = 'SIGNED'
              AND r.itemized = false
            """, nativeQuery = true)
    BigDecimal sumSignedActReceipts(@Param("projectId") UUID projectId);
}
