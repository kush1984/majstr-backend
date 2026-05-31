package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CatalogItemRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 100) String category,
        @NotNull ItemType type,
        @NotNull Unit unit,
        @NotNull @DecimalMin(value = "0.01", message = "defaultPrice must be greater than 0")
        @Digits(integer = 13, fraction = 2) BigDecimal defaultPrice
) {}
