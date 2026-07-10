package com.majstr.backend.dto;

import com.majstr.backend.entity.EstimateStatus;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record EstimateUpdateRequest(
        @NotNull EstimateStatus status,
        LocalDate validUntil,
        @Size(max = 4000) String notes,
        @Size(max = 255) String name,
        /** Deposit (завдаток); null clears it. Balance is computed server-side. */
        @PositiveOrZero @Digits(integer = 13, fraction = 2) BigDecimal depositAmount
) {}
