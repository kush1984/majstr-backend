package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.PercentBaseKind;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;

public record PublicEstimateItemView(
        ItemType type,
        String name,
        Unit unit,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        int sortOrder,
        /**
         * The section this line sits in, or null for none. The client page groups by it, in the
         * order sortOrder puts the lines — the same arrangement the master dragged into place and
         * the same one the PDF shows, so the three never disagree.
         */
        String category,
        /**
         * Null unless this is a PERCENT line — lets the page tell a TOTAL-kind line (which the app's
         * own {@code TypeBreakdown} backs OUT of the type's subtotal to show a pre-adjustment "base"
         * figure, per-type, next to a small Знижка/Надбавка sub-line) from a POSITION/MANUAL one
         * (already fully baked into the subtotal, nothing to back out).
         */
        PercentBaseKind percentBaseKind,
        /**
         * Non-null only for a line frozen into a consolidated rollup (V92) — same "back it out of the
         * subtotal" treatment as a TOTAL-kind line, mirroring {@code TypeBreakdown}'s {@code
         * frozenMarkup}/{@code frozenDiscount}.
         */
        String baseOriginLabel
) {}
