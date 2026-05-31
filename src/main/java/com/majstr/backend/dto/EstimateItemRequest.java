package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record EstimateItemRequest(
        @NotNull ItemType type,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 100) String category,
        @NotNull Unit unit,
        @NotNull @DecimalMin(value = "0.001", message = "quantity must be greater than 0")
        @Digits(integer = 12, fraction = 3) BigDecimal quantity,
        @NotNull @DecimalMin(value = "0.01", message = "unitPrice must be greater than 0")
        @Digits(integer = 13, fraction = 2) BigDecimal unitPrice,
        @PositiveOrZero Integer sortOrder
) {}
