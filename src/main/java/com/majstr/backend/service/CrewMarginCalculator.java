package com.majstr.backend.service;

import com.majstr.backend.dto.CrewMarginResponse;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.PercentBaseKind;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * <h2>A line with no crew price contributes NOTHING — and «%» is where that gets interesting</h2>
 * V85's javadoc used to say the opposite — that a line added to the copy afterwards is «whole line
 * margin». That is the same mistake that got «Прибуток» hidden: work the crew may well be doing and
 * being paid for, counted as the master's earning because the data does not say otherwise. Such
 * lines are reported separately ({@link CrewMarginResponse#unpricedCount()}) so the screen can name
 * them instead of quietly inflating a figure.
 *
 * <p>For an ordinary line «contributes nothing» is free: the crew view keeps the client's own price,
 * so the two sides agree and the difference is zero. A <b>«%» line added after the copy</b> was not
 * (review B-72): it was re-measured against the crew's SMALLER base, so «Знижка −10 %» came out
 * −1 000 in the crew view against −1 200 in the client's and the master was shown 1 800 of margin on
 * a discount he had just given away. The owner's rule: <b>an unpriced line is FROZEN at its client
 * amount in the crew view, and a negative one is frozen at ZERO</b> — a discount the master decided
 * to give comes out of his own margin, not the crew's pay. On that same sheet the figure is 800.</p>
 *
 * <p>The freeze uses the mechanism that already exists for it: the crew's copy of such a line is
 * marked {@code baseDetached} and carries the client's amount, and {@link EstimateMath} leaves a
 * detached line at what it last computed. Its KIND is untouched, which is what keeps its place in
 * the subtotals identical to the client view — a «% від кошторису» line is in no base on either
 * side, a «% від позиції» is in both.</p>
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
     * @param acceptedLines margin already accepted by SIGNED acts on ORDINARY lines, computed by
     *                      the caller from the act lines (this class has no repository)
     * @param closedByItem  what SIGNED acts have billed for each line of the object (the act-line
     *                      money twin of the closed quantity), keyed by estimate item id — the base
     *                      the acts' own adjustments were prorated over
     */
    static CrewMarginResponse of(Estimate estimate, List<EstimateItem> items,
                                 BigDecimal acceptedLines, Map<UUID, BigDecimal> closedByItem) {
        // Deliberately NOT gated on `duplicatedFromId`. That column is ON DELETE SET NULL, so a
        // master who tidied away the crew's original sheet would lose the figure computed from his
        // own copy's lines — which is the exact scenario V85 stores a per-LINE crew price for.
        // `crewPriced` is written only by duplicate() (and V149's backfill) and never cleared, so it
        // alone is both sufficient and stable. It replaced `markupPercent > 0` (review B-105): that
        // is the sign of the last step, and a −5 % copy of a +20 % copy still holds the crew's
        // prices and has a real margin.
        if (!estimate.isCrewPriced()) {
            return null;
        }
        List<EstimateItem> clientLines = detach(items, false);
        EstimateMath.Totals client = EstimateMath.recalculate(clientLines);
        Crew crew = crewView(items);

        BigDecimal unpricedTotal = BigDecimal.ZERO;
        int unpricedCount = 0;
        for (EstimateItem item : items) {
            if (item.getSourceUnitPrice() == null) {
                unpricedCount++;
                unpricedTotal = unpricedTotal.add(amountOf(item));
            }
        }
        BigDecimal margin = client.total().subtract(crew.total());
        return new CrewMarginResponse(
                money(crew.total()),
                money(margin),
                money(accepted(acceptedLines, clientLines, crew.lines(), closedByItem)),
                unpricedCount,
                money(unpricedTotal));
    }

    /**
     * The margin the acts have already accepted.
     *
     * <p>Ordinary lines are per-line and exact — {@code Σ (act price − crew price) × act quantity}.
     * A «%» line can never BE an act line (B-57 refuses the unit outright), so an estimate's
     * percentages reach the act as one prorated ADJUSTMENT line per type (B-55); the margin they
     * carry is the client's amount minus the crew's own, scaled by how much of them travelled.
     * Under the freeze rule above an unpriced discount has no crew amount at all, so the whole of
     * it lands on the master — which is what makes this sum meet the margin to the kopeck when the
     * acts have closed everything.</p>
     */
    private static BigDecimal accepted(BigDecimal acceptedLines, List<EstimateItem> clientLines,
                                       List<EstimateItem> crewLines, Map<UUID, BigDecimal> closedByItem) {
        BigDecimal lines = acceptedLines == null ? BigDecimal.ZERO : acceptedLines;
        if (closedByItem == null || closedByItem.isEmpty()) {
            return lines;
        }
        // The SAME per-line proration the acts' own adjustments use (review B-99), run twice — over
        // the client's sheet and over the crew's — with the crew's closed share of each line scaled
        // from the client's. One ratio for every «%» line (Σ adjustments ÷ Σ client %) mixed a
        // position percentage with an estimate-wide one: an act closing only the line a +10 %
        // followed reported −109 ₴ of margin, one closing only the other reported more than the
        // whole sheet's margin, and a +10 % and −10 % that cancel skipped the branch entirely.
        Map<UUID, BigDecimal> crewClosed = new HashMap<>();
        Map<UUID, BigDecimal> crewAmount = new HashMap<>();
        for (EstimateItem crewLine : crewLines) {
            crewAmount.put(crewLine.getId(), amountOf(crewLine));
        }
        for (EstimateItem clientLine : clientLines) {
            BigDecimal closed = closedByItem.get(clientLine.getId());
            BigDecimal clientAmount = amountOf(clientLine);
            if (closed == null || clientLine.getUnit() == Unit.PERCENT || clientAmount.signum() == 0) {
                continue;
            }
            BigDecimal share = closed.divide(clientAmount, 10, EstimateMath.MONEY_ROUNDING);
            crewClosed.put(clientLine.getId(),
                    crewAmount.getOrDefault(clientLine.getId(), BigDecimal.ZERO).multiply(share));
        }
        BigDecimal percentMargin = BigDecimal.ZERO;
        Map<ItemType, BigDecimal> clientCarried = ActAdjustmentCalculator.adjustmentsPerType(clientLines, closedByItem);
        Map<ItemType, BigDecimal> crewCarried = ActAdjustmentCalculator.adjustmentsPerType(crewLines, crewClosed);
        for (Map.Entry<ItemType, BigDecimal> e : clientCarried.entrySet()) {
            percentMargin = percentMargin.add(e.getValue().subtract(crewCarried.getOrDefault(e.getKey(), BigDecimal.ZERO)));
        }
        return lines.add(percentMargin);
    }

    /** The crew's total and its recalculated lines. */
    private record Crew(BigDecimal total, List<EstimateItem> lines) {}

    /**
     * The crew's own view of the sheet: the same lines at the prices the crew charged, with every
     * line the master added afterwards frozen where the client's sheet has it.
     */
    private static Crew crewView(List<EstimateItem> items) {
        List<EstimateItem> lines = detach(items, true);
        EstimateMath.Totals totals = EstimateMath.recalculate(lines);
        return new Crew(totals.total(), lines);
    }

    /**
     * Detached copies of the lines, optionally wearing the crew's numbers.
     *
     * <p>Only the four fields the arithmetic reads are swapped. A PERCENT line's crew figure is a
     * PERCENT, not a price — {@code duplicate()} stores the original percent in
     * {@code source_unit_price} for exactly this — so it goes back into the quantity, and its base
     * is then whatever the crew view makes of the line it points at.</p>
     *
     * <p>In the CREW list an unpriced PERCENT line is FROZEN instead (B-72) — detached, carrying
     * the client's own amount, zero if that amount is negative.</p>
     */
    private static List<EstimateItem> detach(List<EstimateItem> items, boolean crew) {
        List<EstimateItem> out = new ArrayList<>(items.size());
        for (EstimateItem item : items) {
            boolean percent = item.getUnit() == Unit.PERCENT;
            boolean unpriced = item.getSourceUnitPrice() == null;
            boolean priced = crew && !unpriced;
            if (crew && percent && unpriced) {
                // FROZEN: detached, carrying the client's own amount (a negative one at zero), so
                // the pass leaves it exactly where the client's sheet has it. Its kind is kept, and
                // that is what keeps its place in the subtotals identical to the client view: a
                // «% від кошторису» line is in no base either way, a «% від позиції» is in both.
                out.add(EstimateItem.builder()
                        .id(item.getId())
                        .type(item.getType())
                        .unit(Unit.PERCENT)
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .percentBaseKind(item.getPercentBaseKind())
                        .percentBaseItemId(item.getPercentBaseItemId())
                        .baseDetached(true)
                        .lineTotal(frozen(item))
                        .build());
                continue;
            }
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

    /** The client's amount, except that a line the master gave away is frozen at zero. */
    private static BigDecimal frozen(EstimateItem item) {
        BigDecimal amount = amountOf(item);
        return amount.signum() < 0 ? BigDecimal.ZERO : amount;
    }

    private static BigDecimal amountOf(EstimateItem item) {
        return item.getLineTotal() == null ? BigDecimal.ZERO : item.getLineTotal();
    }

    /** A PERCENT line with no kind recorded is MANUAL — the same reading {@link EstimateMath} uses. */
    private static PercentBaseKind kindOf(EstimateItem item) {
        return item.getPercentBaseKind() == null ? PercentBaseKind.MANUAL : item.getPercentBaseKind();
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(EstimateMath.MONEY_SCALE, EstimateMath.MONEY_ROUNDING);
    }
}
