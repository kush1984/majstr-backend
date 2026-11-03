package com.majstr.backend.dto;

import com.majstr.backend.entity.CashCategory;
import com.majstr.backend.entity.CashDirection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The master's own money for one period (V135, «Мої гроші») — three sources shown as one movement.
 *
 * @param from     inclusive first day of the period, resolved in {@code Europe/Kyiv}
 * @param to       inclusive last day
 * @param income   the master's own money in: payment for work, his personal income. The client's
 *                 MATERIAL money is not here (object-money iteration) — see {@code materials}
 * @param expense  the master's own money out: object expenses, his personal spending, and material
 *                 billed to the client through an act. A till receipt «клієнт поверне» is not here
 * @param earned   {@code income − expense} — one subtraction. Both halves of the client's material
 *                 money (what the master laid out, what the client handed over) stay out of it, so
 *                 a purchase and its reimbursement falling in different months no longer swing it
 * @param refunds  Σ of the client's material money that ARRIVED in the period — an info line,
 *                 subtracted from nothing and added to nothing
 * @param entries  newest day first; empty for a YEAR period, which returns {@code months} instead —
 *                 two thousand rows on a phone is not a screen anyone reads
 * @param months    per-month totals, filled only for a YEAR period (newest first)
 * @param truncated the list was cut at the server's cap. The TOTALS above are computed over
 *                  everything regardless — a screen quietly showing part of a month is worse than
 *                  one that says so
 * @param materials what each client still owes for material, or still has unspent with the master —
 *                  ALL TIME, not the period: a debt does not end with the month. Only objects whose
 *                  balance is not zero, largest debt first, narrowed by the same object filter
 */
public record CashFlowResponse(
        LocalDate from,
        LocalDate to,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal earned,
        BigDecimal refunds,
        List<Entry> entries,
        List<MonthTotal> months,
        boolean truncated,
        List<MaterialBalance> materials,
        List<ScopeTotal> objects,
        ScopeTotal personal
) {

    /**
     * One slice of the period's totals: an object, or (projectId null) the rows that belong to none.
     * The same «master's own money» rule as the top-level figures, so the slices add up to them:
     * Σ objects + personal = income / expense / earned. An object whose only rows this period are
     * the client's material money is listed with zeros — it moved, and its balance is worth seeing.
     */
    public record ScopeTotal(
            UUID projectId,
            String projectName,
            BigDecimal income,
            BigDecimal expense,
            BigDecimal earned
    ) {}

    /**
     * One line of the feed, whichever table it came from.
     *
     * @param kind           which table the row lives in. EVERY kind is editable and deletable from
     *                       this screen (master's ruling) — it is a second door to one record, not a
     *                       second copy, and the write still goes through the object's own service
     * @param happenedOn     the authoritative day
     * @param happenedAt     time within the day, or null for object rows, which carry a bare date.
     *                       Ordering already accounts for that; this is here so a client can show it
     * @param projectId      the object this money belongs to, or null for an off-object row
     * @param materialRefund income the client handed over for material — before or after the
     *                       purchase
     * @param clientMaterial the row is the client's material money, not the master's: a payment
     *                       «на матеріал» or a till receipt «клієнт поверне». Shown, but counted in
     *                       none of {@code income}/{@code expense}/{@code earned}
     * @param noteLocked     the text belongs to something else and an edit would be a silent no-op:
     *                       a PLANNED receipt's name is its stage's purpose, and {@code editReceipt}
     *                       deliberately leaves it alone
     * @param readOnly       the row may be shown but not changed from here — an {@code ACT_RECEIPT}
     *                       is frozen inside a signed act's {@code doc_hash}. The client hides the
     *                       edit affordance rather than offering a tap that can only 409
     * @param hasPhoto       a photo of the paper is stored with the row
     */
    public record Entry(
            UUID id,
            CashEntryKind kind,
            CashDirection direction,
            BigDecimal amount,
            CashCategory category,
            String note,
            LocalDate happenedOn,
            Instant happenedAt,
            UUID projectId,
            String projectName,
            boolean materialRefund,
            boolean clientMaterial,
            boolean noteLocked,
            boolean readOnly,
            boolean hasPhoto,
            /**
             * Of a receipt bought for a SIGNED act, the part the client's «на матеріал» money paid
             * ({@code MaterialRefundSplit.toActs}) — null otherwise. {@code amount} is then what left
             * the master's own pocket; the paper's figure is {@code amount + clientShare}.
             */
            BigDecimal clientShare
    ) {

        /** This row with {@code share} of it paid by the client's material money. */
        public Entry paidByClient(BigDecimal share) {
            return new Entry(id, kind, direction, amount.subtract(share), category, note, happenedOn,
                    happenedAt, projectId, projectName, materialRefund, clientMaterial, noteLocked,
                    readOnly, hasPhoto, share);
        }
    }

    /** One month of a YEAR view. {@code month} is its first day, so the client can re-query it. */
    public record MonthTotal(
            LocalDate month,
            BigDecimal income,
            BigDecimal expense,
            BigDecimal earned
    ) {}

    /**
     * One object's material balance with its client.
     *
     * @param bought      Σ of the till receipts «клієнт поверне» not billed on an act
     * @param given       Σ of the client's payments «на матеріал»
     * @param outstanding {@code bought − given}: positive = the client owes, negative = his money the
     *                    master has not spent on material yet
     */
    public record MaterialBalance(
            UUID projectId,
            String projectName,
            BigDecimal bought,
            BigDecimal given,
            /** Of {@code given}, what paid for the material on signed acts — no longer the client's. */
            BigDecimal toActs,
            BigDecimal outstanding
    ) {}
}
