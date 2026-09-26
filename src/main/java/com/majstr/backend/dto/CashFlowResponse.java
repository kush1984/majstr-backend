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
 * @param income   everything that came in, refunds included — it really did arrive
 * @param expense  everything that went out
 * @param earned   {@code income − expense}. The figure that answers «скільки я заробив», and since
 *                 review B-33 it is one subtraction, not two: the material the client pays back is
 *                 netted by its own COST being in {@code expense} — the till receipt he is owed for
 *                 is now a feed row. Subtracting the refund on top of that (the V135 formula)
 *                 charged the master for the same material twice, and in a month where he had
 *                 bought but not yet been paid back it read the 8 000 ₴ he was out of pocket as
 *                 pure profit
 * @param refunds  Σ of the income marked «повернення за матеріал» — an INFO line now, subtracted
 *                 from nothing: it explains which part of «Прийшло» was not payment for work
 * @param entries  newest day first; empty for a YEAR period, which returns {@code months} instead —
 *                 two thousand rows on a phone is not a screen anyone reads
 * @param months    per-month totals, filled only for a YEAR period (newest first)
 * @param truncated the list was cut at the server's cap. The TOTALS above are computed over
 *                  everything regardless — a screen quietly showing part of a month is worse than
 *                  one that says so
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
        boolean truncated
) {

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
     * @param materialRefund income the client paid back for material. Since B-33 it labels the row
     *                       and nothing more: the material it repays is itself a feed row now, so
     *                       «Заробив» nets out on its own
     * @param noteLocked     the text belongs to something else and an edit would be a silent no-op:
     *                       a PLANNED receipt's name is its stage's purpose, and {@code editReceipt}
     *                       deliberately leaves it alone
     * @param readOnly       the row may be shown but not changed from here — an {@code ACT_RECEIPT}
     *                       is frozen inside a signed act's {@code doc_hash}. The client hides the
     *                       edit affordance rather than offering a tap that can only 409
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
            boolean noteLocked,
            boolean readOnly
    ) {}

    /** One month of a YEAR view. {@code month} is its first day, so the client can re-query it. */
    public record MonthTotal(
            LocalDate month,
            BigDecimal income,
            BigDecimal expense,
            BigDecimal earned
    ) {}
}
