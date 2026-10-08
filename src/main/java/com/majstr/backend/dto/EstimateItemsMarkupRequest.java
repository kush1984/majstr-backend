package com.majstr.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The lines whose price moves, and by how much — «Націнка на вибрані позиції».
 *
 * <p>Shaped like {@link EstimateDuplicateRequest}: an UNSIGNED magnitude plus a direction, so a
 * discount is a markup with a minus and nothing downstream has to branch on which it is. The two
 * bounds are the same too — a discount may not exceed 100 % (the price would go negative), a markup
 * stops at 999,99 %, which is what {@code estimates.markup_percent NUMERIC(5,2)} can hold: the old
 * {@code @DecimalMax("1000")} let 1000 % through the validator and then overflowed the column on the
 * INSERT, a 500 on a figure the master had been allowed to type (review B-75).</p>
 *
 * <p>Unlike the duplicate, {@code itemIds} is REQUIRED. There is no "all WORK lines" default: this
 * runs on the estimate the master is looking at, and a mistyped percent applied to everything by
 * omission is not a mistake he could undo.</p>
 */
public record EstimateItemsMarkupRequest(
        @NotEmpty @Size(max = 500) List<UUID> itemIds,
        @NotNull @DecimalMin("0") @DecimalMax("999.99") @Digits(integer = 3, fraction = 2)
        BigDecimal percent,
        /**
         * Whether {@code markupPercent} is a DISCOUNT rather than a rise. A wrapper with
         * {@code @NotNull}, never a primitive (review B-38): the global
         * {@code fail-on-null-for-primitives: false} (V135) turned an omitted field from a 400 into a
         * silent {@code false}, so a client drift would quietly raise prices where the master meant
         * to cut them. A boolean that reverses the SIGN of money has to be stated.
         */
        @NotNull Boolean discount
) {
    @jakarta.validation.constraints.AssertTrue(message = "a discount cannot exceed 100%")
    public boolean isDirectionWithinBounds() {
        // A null `discount` is the @NotNull's 400 to report; unboxing it here threw HV000090 → 500 (B-100).
        return !Boolean.TRUE.equals(discount) || percent == null || percent.compareTo(new BigDecimal("100")) <= 0;
    }
}
