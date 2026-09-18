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
 * @param earned   {@code income − materialRefunds − expense}. The figure that answers «скільки я
 *                 заробив»: material the client merely paid back is not earnings, and counting it
 *                 would inflate a month by exactly that material
 * @param refunds  Σ of the income marked «повернення за матеріал» — shown so the gap between
 *                 «Прийшло» and «Заробив» is explained rather than mysterious
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
     * @param materialRefund income the client paid back for material — in the movement, out of
     *                       «Заробив»
     * @param noteLocked     the text belongs to something else and an edit would be a silent no-op:
     *                       a PLANNED receipt's name is its stage's purpose, and {@code editReceipt}
     *                       deliberately leaves it alone
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
            boolean noteLocked
    ) {}

    /** One month of a YEAR view. {@code month} is its first day, so the client can re-query it. */
    public record MonthTotal(
            LocalDate month,
            BigDecimal income,
            BigDecimal expense,
            BigDecimal earned
    ) {}
}
