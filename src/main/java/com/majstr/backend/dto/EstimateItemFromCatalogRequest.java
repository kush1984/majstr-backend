package com.majstr.backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * Add one catalog position to an estimate. The line takes the row's own trade and folder: every
 * trade holds its own rows (V220), so the row the master tapped is the trade he works in.
 */
public record EstimateItemFromCatalogRequest(
        @NotNull @DecimalMin(value = "0.001", message = "quantity must be greater than 0")
        @Digits(integer = 12, fraction = 3) BigDecimal quantity,
        @PositiveOrZero Integer sortOrder
) {
}
