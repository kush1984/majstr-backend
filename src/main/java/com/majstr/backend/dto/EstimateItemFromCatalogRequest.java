package com.majstr.backend.dto;

import com.majstr.backend.entity.Trade;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * @param trade the trade whose FOLDER the master tapped this position in, when the picker tree
 *              knows it. A position two trades both ship is stored once (V118) and shown under
 *              both, so the stored row alone cannot say which work this line is — see
 *              {@link com.majstr.backend.service.CatalogFiling}. Optional and never trusted
 *              blindly: it is honoured only if the shipped library files this exact name under
 *              that trade, and ignored otherwise.
 */
public record EstimateItemFromCatalogRequest(
        @NotNull @DecimalMin(value = "0.001", message = "quantity must be greater than 0")
        @Digits(integer = 12, fraction = 3) BigDecimal quantity,
        @PositiveOrZero Integer sortOrder,
        Trade trade
) {
    /** Callers that add without a folder in hand — the autocomplete, tests, offline replay. */
    public EstimateItemFromCatalogRequest(BigDecimal quantity, Integer sortOrder) {
        this(quantity, sortOrder, null);
    }
}
