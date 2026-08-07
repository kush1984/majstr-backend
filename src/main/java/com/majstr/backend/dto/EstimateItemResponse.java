package com.majstr.backend.dto;

import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.PercentBaseKind;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.MeasurementRefs;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

public record EstimateItemResponse(
        UUID id,
        ItemType type,
        String name,
        String category,
        Unit unit,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        int sortOrder,
        /** Measurement elements this line's quantity was summed from (empty = none) —
         *  drives the "Вибрати з замірів" pre-selection. */
        List<UUID> measurementRefs,
        /** True when the master edited the quantity by hand — drives the overwrite warning. */
        boolean quantityManual,
        /**
         * For a {@code PERCENT} line: what it is a percentage OF — MANUAL / POSITION / TOTAL.
         * Null on every other line. The client needs it to word the row («10 % від «Шафа»») and to
         * preselect the right base in the editor.
         */
        PercentBaseKind percentBaseKind,
        /** The line this percentage is measured against ({@code POSITION}); null otherwise. */
        UUID percentBaseItemId,
        /**
         * The live link is off — the master typed the amount himself, or the base was deleted. The
         * row keeps its last computed amount and the screen offers to re-attach it.
         */
        boolean baseDetached,
        /**
         * Snapshot of what a FROZEN percent line meant before consolidation — the signed percent,
         * what it was a share of, and the source estimate's name. Null on every other line (a
         * detached-but-not-frozen line, e.g. a deleted POSITION base, has no origin snapshot either
         * — {@code baseDetached} alone covers that case).
         */
        String baseOriginLabel
) {
    public static EstimateItemResponse from(EstimateItem item) {
        // The STORED amount (V88), never recomputed here. A percentage of the estimate's own
        // subtotal cannot be derived from one row, and re-deriving on read is exactly how a signed
        // estimate would drift behind the client's back.
        BigDecimal lineTotal = item.getLineTotal() == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : item.getLineTotal();
        return new EstimateItemResponse(
                item.getId(),
                item.getType(),
                item.getName(),
                item.getCategory(),
                item.getUnit(),
                item.getQuantity(),
                item.getUnitPrice(),
                lineTotal,
                item.getSortOrder(),
                MeasurementRefs.parse(item.getMeasurementRefs()),
                item.isQuantityManual(),
                item.getPercentBaseKind(),
                item.getPercentBaseItemId(),
                item.isBaseDetached(),
                item.getBaseOriginLabel()
        );
    }
}
