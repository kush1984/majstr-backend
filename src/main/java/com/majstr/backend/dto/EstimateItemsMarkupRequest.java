package com.majstr.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
 * is open-ended up to 1000 %.</p>
 *
 * <p>Unlike the duplicate, {@code itemIds} is REQUIRED. There is no "all WORK lines" default: this
 * runs on the estimate the master is looking at, and a mistyped percent applied to everything by
 * omission is not a mistake he could undo.</p>
 */
public record EstimateItemsMarkupRequest(
        @NotEmpty @Size(max = 500) List<UUID> itemIds,
        @NotNull @DecimalMin("0") @DecimalMax("1000") BigDecimal percent,
        boolean discount
) {
    @jakarta.validation.constraints.AssertTrue(message = "a discount cannot exceed 100%")
    public boolean isDirectionWithinBounds() {
        return !discount || percent == null || percent.compareTo(new BigDecimal("100")) <= 0;
    }
}
