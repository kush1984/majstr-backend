package com.majstr.backend.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Copy an estimate and re-price the chosen lines — the бригадир's two-price workflow.
 *
 * @param name          the copy's name; null → the source's name with the delta appended
 * @param markupPercent the MAGNITUDE of the change, always ≥ 0. {@code discount} decides the sign:
 *                      false → prices go up (націнка), true → down (уцінка). Kept unsigned so the
 *                      direction is an explicit choice the master made, never a stray minus that
 *                      would silently flip the object economy negative with nothing on screen.
 * @param discount      false = markup (add the percent), true = discount (subtract it). A discount is
 *                      the exact same mechanic mirrored: same copy, same {@code sourceUnitPrice} on
 *                      every line, same "parent stops counting" — only the factor is {@code 1 − p/100}
 *                      instead of {@code 1 + p/100}. Capped at 100 % (a bigger discount would drive
 *                      prices to zero or below).
 * @param itemIds       which lines get the change. <b>null means every WORK line</b> — the default
 *                      the picker shows, and the one that matters: materials are bought at cost and
 *                      passed through, so re-pricing them by default would move a client's estimate
 *                      in a way the master never asked for. A foreman who does want to touch a
 *                      material ticks it himself, and then it arrives in this list.
 */
public record EstimateDuplicateRequest(
        @Size(max = 255) String name,
        @NotNull @DecimalMin("0") @DecimalMax("999.99") @Digits(integer = 3, fraction = 2)
        BigDecimal markupPercent,
        /**
         * Whether {@code markupPercent} is a DISCOUNT rather than a rise. A wrapper with
         * {@code @NotNull}, never a primitive (review B-38): the global
         * {@code fail-on-null-for-primitives: false} (V135) turned an omitted field from a 400 into a
         * silent {@code false}, so a client drift would quietly raise prices where the master meant
         * to cut them. A boolean that reverses the SIGN of money has to be stated.
         */
        @NotNull Boolean discount,
        List<UUID> itemIds
) {
    /** A discount over 100 % would produce zero or negative prices — reject it as a 400. */
    @AssertTrue(message = "A discount cannot exceed 100%")
    public boolean isDiscountWithinRange() {
        // A null `discount` is the @NotNull's 400 to report; unboxing it here threw HV000090 → 500 (B-100).
        return !Boolean.TRUE.equals(discount) || markupPercent == null
                || markupPercent.compareTo(new BigDecimal("100")) <= 0;
    }
}
