package com.majstr.backend.service;

import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.PercentBaseKind;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ONE place an estimate's money is computed.
 *
 * <p>It exists because «%» stopped being a unit of measure. A percentage line is a share OF
 * something — a sum typed by hand, another line, or the estimate's own subtotal — and that turns a
 * flat list of independent multiplications into a small dependency graph. Six native SQL aggregates
 * used to do the multiplication themselves; they now read the stored {@code line_total} this class
 * writes, because a percentage of the subtotal cannot be expressed as one {@code SUM} at all.</p>
 *
 * <h2>The three-step pass</h2>
 * Deterministic, single-pass, no recursion — and the order is the whole design:
 * <ol>
 *   <li>ordinary lines: {@code quantity × unitPrice};</li>
 *   <li>percentages of a LINE or of a hand-typed sum;</li>
 *   <li>percentages of the TOTAL, all measured against the sum of steps 1 and 2.</li>
 * </ol>
 *
 * <p><b>Why a percent of a percent is forbidden.</b> The base can only ever be an ordinary line or
 * the subtotal. That single rule kills cyclic dependencies <em>by definition</em> — there is no
 * graph to walk, no cycle detection to write and get wrong, only a filter on what the picker
 * offers. It also makes step 2 order-independent within itself.</p>
 *
 * <p><b>Why several TOTAL lines share one base.</b> «Транспортні 20 %» and «Непередбачені 10 %»
 * both measure the same estimate. Compounding them would make the result depend on which was
 * entered first, which nobody could explain to a client.</p>
 *
 * <p>The PWA mirrors this pass in {@code useEstimate.recompute} so an offline estimate shows the
 * right numbers before it syncs. <b>The mirror must be changed in the same commit as this file</b> —
 * and note that the client computes for DISPLAY only: {@code line_total} is written by the server
 * and never accepted from a request.</p>
 */
final class EstimateMath {

    private EstimateMath() {
    }

    static final int MONEY_SCALE = 2;
    static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** Works / materials / total, all derived from the same stored line amounts. */
    record Totals(BigDecimal works, BigDecimal materials, BigDecimal total) {}

    /**
     * Recompute every line's amount in place, then report the subtotals.
     *
     * <p>Rounds per line and then sums, which is what keeps the arithmetic a master can check:
     * line amounts add up to the subtotals, subtotals add up to the total.</p>
     */
    static Totals recalculate(List<EstimateItem> items) {
        Map<UUID, BigDecimal> amountById = new HashMap<>();

        // 1. Ordinary lines. Also the only lines a percentage may point at, so they must be done
        //    before anything else can read them.
        for (EstimateItem item : items) {
            if (item.getUnit() != Unit.PERCENT) {
                BigDecimal amount = round(item.getQuantity().multiply(item.getUnitPrice()));
                item.setLineTotal(amount);
                amountById.put(item.getId(), amount);
            }
        }

        // 2. Percentages of a line or of a hand-typed sum.
        for (EstimateItem item : items) {
            if (item.getUnit() != Unit.PERCENT || kindOf(item) == PercentBaseKind.TOTAL) {
                continue;
            }
            item.setLineTotal(percentAmount(item, baseFor(item, amountById)));
        }

        // 3. Percentages of the TOTAL — every one of them against the SAME base.
        BigDecimal baseForTotals = sumOf(items, i -> kindOf(i) != PercentBaseKind.TOTAL);
        for (EstimateItem item : items) {
            if (item.getUnit() == Unit.PERCENT && kindOf(item) == PercentBaseKind.TOTAL) {
                item.setLineTotal(percentAmount(item, baseForTotals));
            }
        }

        BigDecimal works = sumOf(items, i -> i.getType() == ItemType.WORK);
        BigDecimal materials = sumOf(items, i -> i.getType() == ItemType.MATERIAL);
        return new Totals(works, materials, works.add(materials));
    }

    /**
     * What this percentage is measured against, or {@code null} when the link is off.
     *
     * <p>A detached line keeps whatever it last computed: the master either typed the amount
     * himself — manual beats automatic, the same rule {@code quantityManual} follows in
     * measurements — or the base was deleted, and zeroing a line he is charging for would be data
     * loss. A base that is somehow missing anyway is treated as detached rather than as zero.</p>
     */
    private static BigDecimal baseFor(EstimateItem item, Map<UUID, BigDecimal> amountById) {
        if (item.isBaseDetached()) {
            return null;
        }
        return switch (kindOf(item)) {
            case MANUAL -> item.getUnitPrice();
            case POSITION -> item.getPercentBaseItemId() == null
                    ? null
                    : amountById.get(item.getPercentBaseItemId());
            // Handled in step 3; never reached here.
            case TOTAL -> null;
        };
    }

    private static BigDecimal percentAmount(EstimateItem item, BigDecimal base) {
        if (base == null) {
            // Keep the last computed amount rather than inventing a new one.
            return item.getLineTotal() == null ? zero() : item.getLineTotal();
        }
        return round(base.multiply(item.getQuantity()).divide(HUNDRED, 10, MONEY_ROUNDING));
    }

    /**
     * A PERCENT line with no kind recorded is read as MANUAL — the shape it already had, where the
     * price column held the sum. Defaulting to POSITION instead would make it point at nothing.
     */
    private static PercentBaseKind kindOf(EstimateItem item) {
        if (item.getUnit() != Unit.PERCENT) {
            return null;
        }
        return item.getPercentBaseKind() == null ? PercentBaseKind.MANUAL : item.getPercentBaseKind();
    }

    private static BigDecimal sumOf(List<EstimateItem> items, java.util.function.Predicate<EstimateItem> keep) {
        BigDecimal sum = zero();
        for (EstimateItem item : items) {
            if (keep.test(item) && item.getLineTotal() != null) {
                sum = sum.add(item.getLineTotal());
            }
        }
        return sum;
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }
}
