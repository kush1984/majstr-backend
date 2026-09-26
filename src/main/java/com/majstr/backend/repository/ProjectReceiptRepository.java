package com.majstr.backend.repository;

import com.majstr.backend.entity.ProjectReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectReceiptRepository extends JpaRepository<ProjectReceipt, UUID> {

    /** The object's receipts in the ONE order they are shown in — newest paper first, undated on
     *  top, exactly like {@code WorkActReceiptRepository.findByWorkActIdNewestFirst}: a receipt with
     *  no date is one the master still has to look at, so it belongs where he lands. */
    @Query("""
            SELECT r FROM ProjectReceipt r
            WHERE r.projectId = :projectId
            ORDER BY r.issuedAt DESC NULLS FIRST, r.sortOrder ASC, r.createdAt ASC
            """)
    List<ProjectReceipt> findByProjectIdNewestFirst(@Param("projectId") UUID projectId);

    Optional<ProjectReceipt> findByIdAndProjectId(UUID id, UUID projectId);

    /**
     * Every receipt on this object whose paper is identified by its printed fiscal code, OLDEST
     * FIRST (review item B-04). The order is the contract: it decides which of a pair is «the
     * original» and which gets the duplicate warning, so it must not be relaxed to the display
     * order, which is newest-first and re-sorts whenever a date is typed.
     *
     * <p>A BLANK code is not an identity (B-21): {@code IS NOT NULL} alone let {@code ''} through,
     * and one blank key made every such receipt the twin of every other. Writes normalise now, so
     * this is belt-and-braces for the rows V136 could not have reached.</p>
     */
    @Query("""
            SELECT r FROM ProjectReceipt r
            WHERE r.projectId = :projectId
              AND r.fiscalFn IS NOT NULL AND TRIM(r.fiscalFn) <> ''
              AND r.fiscalId IS NOT NULL AND TRIM(r.fiscalId) <> ''
            ORDER BY r.createdAt ASC, r.sortOrder ASC
            """)
    List<ProjectReceipt> findIdentifiedByProjectId(@Param("projectId") UUID projectId);

    /** Does an object receipt own this {@code object_expenses} row? The BACK-LINK is the test, never
     *  {@code source = RECEIPT}: {@code ActAddendumCreator.postReceiptExpenses} posts MATERIALS
     *  /RECEIPT rows too, and those belong to nobody — they must stay editable in the journal. */
    boolean existsByExpenseId(UUID expenseId);

    long countByProjectId(UUID projectId);

    @Query("SELECT COALESCE(MAX(r.sortOrder), -1) FROM ProjectReceipt r WHERE r.projectId = :projectId")
    int maxSortOrder(@Param("projectId") UUID projectId);

    /** Σ of what the client is expected to pay back — the receivable shown in the FREE-visible half
     *  of the object economy. Only the reimbursable rows: a receipt the master flipped to «моя
     *  витрата» is an {@code object_expenses} row and is counted there instead, never twice.
     *
     *  <p>A receipt already billed on a signed act leaves this axis too (B-04): the act's ADDENDUM
     *  moved that money into «За договором», where the client owes it under a signed document, so
     *  counting it here as well would show the same debt twice. {@code billed_on_act_id} is NULL for
     *  every row that predates V134, so this filter changed no existing figure.</p> */
    @Query("""
            SELECT COALESCE(SUM(r.amount), 0) FROM ProjectReceipt r
            WHERE r.projectId = :projectId AND r.reimbursable = true
              AND r.billedOnActId IS NULL
            """)
    BigDecimal sumReimbursable(@Param("projectId") UUID projectId);

    /** Counts exactly what {@link #sumReimbursable} sums — the two are shown side by side («N чеків
     *  на X ₴»), so they must never filter differently. */
    @Query("""
            SELECT COUNT(r) FROM ProjectReceipt r
            WHERE r.projectId = :projectId AND r.reimbursable = true
              AND r.billedOnActId IS NULL
            """)
    long countReimbursable(@Param("projectId") UUID projectId);

    /** Is any receipt still unpriced? Amount 0 is a normal intermediate state (the photo is saved
     *  before it is read), so the screen says how many are still waiting for a number. */
    @Query("""
            SELECT COUNT(r) FROM ProjectReceipt r
            WHERE r.projectId = :projectId AND r.amount <= 0
            """)
    long countUnpriced(@Param("projectId") UUID projectId);

    /**
     * Every till receipt across ALL of one master's objects in a period that came out of HIS OWN
     * pocket and is recorded nowhere else (review B-33) — «Мої гроші» reads this beside
     * {@code object_expenses} and {@code cash_entry}.
     *
     * <p>The object economy says a reimbursable receipt is a RECEIVABLE, not a cost, and that ruling
     * is untouched — but the CASH screen answers a different question. The money did leave his
     * pocket at the till, so a month in which he bought 8 000 ₴ of material and has not been paid
     * back yet is not a month in which he earned the whole contract. Until this existed «Заробив»
     * counted it as pure profit and the reimbursement, when it arrived, was subtracted a second
     * time — the master saw 8 000 of his own money read first as earnings and then as a loss.</p>
     *
     * <p>Only the rows nothing else records: an own-cost receipt ({@code reimbursable = false}) is
     * already an {@code object_expenses} row, and a receipt billed on an act that posts its receipts
     * to expenses is that act's expense — counting either here would double the cost. What is left
     * is the reimbursable paper, billed on a {@code receipts_to_expenses = false} act or on no act
     * at all.</p>
     *
     * <p>{@code issuedAt} is the day the money was spent; a receipt whose date has not been read off
     * the paper yet falls back to the day it was photographed, which is the same day in practice —
     * hence the {@code createdAt} bounds, resolved in {@code Europe/Kyiv} by the caller.</p>
     */
    @Query("""
            SELECT r FROM ProjectReceipt r
            WHERE r.reimbursable = true AND r.amount > 0
              AND r.projectId IN (SELECT p.id FROM Project p WHERE p.owner.id = :ownerId)
              AND (r.billedOnActId IS NULL OR r.billedOnActId IN
                   (SELECT a.id FROM WorkAct a WHERE a.receiptsToExpenses = false))
              AND ((r.issuedAt IS NOT NULL AND r.issuedAt BETWEEN :from AND :to)
                OR (r.issuedAt IS NULL AND r.createdAt >= :fromTs AND r.createdAt < :toTs))
            ORDER BY r.createdAt DESC
            """)
    List<ProjectReceipt> findOutOfPocketByOwnerAndPeriod(@Param("ownerId") UUID ownerId,
                                                         @Param("from") LocalDate from,
                                                         @Param("to") LocalDate to,
                                                         @Param("fromTs") Instant fromTs,
                                                         @Param("toTs") Instant toTs);

    /** Every stored photo of this object's receipts, for the delete that takes the rows with it
     *  (B-26): the cascade removes the only pointer to the file, so the keys are read BEFORE it. */
    @Query("SELECT r.storageKey FROM ProjectReceipt r WHERE r.projectId = :projectId")
    List<String> findStorageKeysByProjectId(@Param("projectId") UUID projectId);
}
