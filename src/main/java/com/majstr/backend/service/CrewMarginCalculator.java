package com.majstr.backend.service;

import com.majstr.backend.dto.CrewMarginResponse;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * «Скільки лишається мені понад бригаду» — the one figure a бригадир can be given honestly.
 *
 * <p><b>Why this and not profit.</b> The object's «Прибуток» was removed for good: its formula
 * subtracted logged expenses, and there is no screen where a master logs an expense against an
 * object — `object_expenses` fills only from act receipts (V110) and a till receipt flipped to
 * «моя витрата» (V129), while crew pay goes into «Мої гроші» as a `CREW` {@code cash_entry} with no
 * object at all. A number that depends on what the master did not forget to record is a number that
 * flatters him. This one depends only on what he TYPED: when he duplicates an estimate with a
 * markup, every line keeps the crew's own price in {@code source_unit_price} (V85).</p>
 *
 * <p><b>The crew view.</b> Rather than a second arithmetic that would one day disagree with the
 * first, the copy's lines are rebuilt with the crew's numbers and run through the SAME
 * {@link EstimateMath#recalculate}. One pass covers «% від позиції», «% від кошторису», discounts
 * and surcharges for free.</p>
 *
 * <p><b>The trap that would corrupt a client's estimate.</b> {@code recalculate} writes
 * {@code lineTotal} back into every entity it is handed, so it must never see the managed rows:
 * a flush would then persist the CREW's prices into the sheet the client signed. Everything below
 * builds DETACHED copies — and they keep their ids, because {@code recalculate} resolves a
 * percentage's base through {@code percentBaseItemId} against exactly those ids.</p>
 *
 * <p><b>A line with no crew price contributes NOTHING.</b> V85's javadoc used to say the opposite —
 * that a line added to the copy afterwards is «whole line margin». That is the same mistake that
 * got «Прибуток» hidden: work the crew may well be doing and being paid for, counted as the
 * master's earning because the data does not say otherwise. Such lines are reported separately
 * ({@link CrewMarginResponse#unpricedCount()}) so the screen can name them instead of quietly
 * inflating a figure.</p>
 */
final class CrewMarginCalculator {

    private CrewMarginCalculator() {
    }

    /**
     * The margin on one estimate, or null when the rule does not apply.
     *
     * <p>Applies only to a duplicate made with a MARKUP. A discount duplicate ({@code
     * markupPercent < 0}) is a cheaper offer to the client, not a crew sheet — there is no margin
     * to report and nothing is shown. An ordinary estimate carries no crew prices at all.</p>
     *
     * @param estimate      the copy
     * @param items         its lines, as loaded — never modified
     * @param acceptedByActs margin already accepted by SIGNED acts, computed by the caller from
     *                      the act lines (this class has no repository)
     */
    static CrewMarginResponse of(Estimate estimate, List<EstimateItem> items, BigDecimal acceptedByActs) {
        // Deliberately NOT gated on `duplicatedFromId`. That column is ON DELETE SET NULL, so a
        // master who tidied away the crew's original sheet would lose the figure computed from his
        // own copy's lines — which is the exact scenario V85 stores a per-LINE crew price for.
        // `markupPercent` is written only by duplicate() and never cleared, so it alone is both
        // sufficient and stable.
        if (estimate.getMarkupPercent() == null || estimate.getMarkupPercent().signum() <= 0) {
            return null;
        }
        EstimateMath.Totals client = EstimateMath.recalculate(detach(items, false));
        EstimateMath.Totals crew = EstimateMath.recalculate(detach(items, true));

        BigDecimal unpricedTotal = BigDecimal.ZERO;
        int unpricedCount = 0;
        for (EstimateItem item : items) {
            if (item.getSourceUnitPrice() == null) {
                unpricedCount++;
                unpricedTotal = unpricedTotal.add(
                        item.getLineTotal() == null ? BigDecimal.ZERO : item.getLineTotal());
            }
        }
        return new CrewMarginResponse(
                money(crew.total()),
                money(client.total().subtract(crew.total())),
                money(acceptedByActs),
                unpricedCount,
                money(unpricedTotal));
    }

    /**
     * Detached copies of the lines, optionally wearing the crew's numbers.
     *
     * <p>Only the four fields the arithmetic reads are swapped. A PERCENT line's crew figure is a
     * PERCENT, not a price — {@code duplicate()} stores the original percent in
     * {@code source_unit_price} for exactly this — so it goes back into the quantity, and its base
     * is then whatever the crew view makes of the line it points at.</p>
     */
    private static List<EstimateItem> detach(List<EstimateItem> items, boolean crew) {
        List<EstimateItem> out = new ArrayList<>(items.size());
        for (EstimateItem item : items) {
            boolean percent = item.getUnit() == Unit.PERCENT;
            boolean priced = crew && item.getSourceUnitPrice() != null;
            out.add(EstimateItem.builder()
                    // The id is load-bearing: percentBaseItemId points at it.
                    .id(item.getId())
                    .type(item.getType())
                    .unit(item.getUnit())
                    .quantity(priced && percent ? item.getSourceUnitPrice() : item.getQuantity())
                    .unitPrice(priced && !percent ? item.getSourceUnitPrice() : item.getUnitPrice())
                    .percentBaseKind(item.getPercentBaseKind())
                    .percentBaseItemId(item.getPercentBaseItemId())
                    .baseDetached(item.isBaseDetached())
                    // A detached line keeps what it last computed, so the stored amount has to
                    // travel with it — see EstimateMath#baseFor.
                    .lineTotal(item.getLineTotal())
                    .build());
        }
        return out;
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(EstimateMath.MONEY_SCALE, EstimateMath.MONEY_ROUNDING);
    }
}
