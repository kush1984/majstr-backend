package com.majstr.backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record EstimateItemFromCatalogRequest(
        @NotNull @DecimalMin(value = "0.001", message = "quantity must be greater than 0")
        @Digits(integer = 12, fraction = 3) BigDecimal quantity,
        @PositiveOrZero Integer sortOrder
) {}
