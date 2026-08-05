package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.PercentBaseKind;
import com.majstr.backend.entity.Unit;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record EstimateItemRequest(
        @NotNull ItemType type,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 100) String category,
        @NotNull Unit unit,
        /** For {@code unit = PERCENT} this is THE PERCENT (10 = 10 %), not a count. */
        @NotNull @DecimalMin(value = "0.001", message = "quantity must be greater than 0")
        @Digits(integer = 12, fraction = 3) BigDecimal quantity,
        /**
         * Price per unit — or, on a {@code PERCENT} line with a {@link PercentBaseKind#MANUAL}
         * base, the base sum itself.
         *
         * <p>Relaxed from {@code @DecimalMin("0.01")} to zero-or-more: a percentage OF ANOTHER LINE
         * or OF THE TOTAL has no price at all, and the old rule would have rejected it with a
         * validation error the master could do nothing about. "Greater than zero" still holds for
         * every other line — see {@link #isUnitPriceUsable()}, where the condition can actually be
         * expressed.</p>
         */
        @NotNull @PositiveOrZero
        @Digits(integer = 13, fraction = 2) BigDecimal unitPrice,
        @PositiveOrZero Integer sortOrder,
        /** Measurement elements the master picked for this line (selection memory). When
         *  present and {@code quantityManual} is false, the server recomputes {@code quantity}
         *  from these (authoritative, unit-checked) and ignores the sent quantity. */
        List<UUID> measurementRefs,
        /** True once the master edited the quantity by hand — then the sent quantity is kept
         *  and measurements don't overwrite it. */
        boolean quantityManual,
        /**
         * For a {@code PERCENT} line: what it is a percentage OF. Null elsewhere; a PERCENT line
         * that omits it is read as {@link PercentBaseKind#MANUAL}, which is the shape «%» already
         * had — the sum in the price column.
         */
        PercentBaseKind percentBaseKind,
        /**
         * The line this percentage is measured against ({@link PercentBaseKind#POSITION}).
         *
         * <p>The server checks it names an ORDINARY line of the same estimate. That check is the
         * only cycle protection the feature needs: a percentage can never point at a percentage, so
         * no cycle can be built in the first place.</p>
         */
        UUID percentBaseItemId
) {

    /**
     * A price of zero is only meaningful where nothing is priced per unit: a percentage measured
     * against another line or against the estimate's total.
     */
    @AssertTrue(message = "unitPrice must be greater than 0")
    public boolean isUnitPriceUsable() {
        if (unitPrice == null) {
            return true; // @NotNull already reports this one
        }
        boolean pricelessPercent = unit == Unit.PERCENT
                && percentBaseKind != null
                && percentBaseKind != PercentBaseKind.MANUAL;
        return pricelessPercent || unitPrice.compareTo(BigDecimal.ZERO) > 0;
    }

    /** A base line may only be named when the base actually is a line. */
    @AssertTrue(message = "percentBaseItemId is only valid with percentBaseKind = POSITION")
    public boolean isBaseItemConsistent() {
        return percentBaseItemId == null || percentBaseKind == PercentBaseKind.POSITION;
    }
}
