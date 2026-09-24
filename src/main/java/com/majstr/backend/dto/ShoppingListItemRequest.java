package com.majstr.backend.dto;

import com.majstr.backend.entity.Unit;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/** A hand-written row. {@code materialId} is optional — the master may buy what we never listed. */
public record ShoppingListItemRequest(
        UUID materialId,
        @NotBlank @Size(max = 255) String name,
        @NotNull Unit unit,
        @NotNull @DecimalMin(value = "0.001") @Digits(integer = 12, fraction = 3) BigDecimal quantity,
        @Size(max = 500) String note
) {}
