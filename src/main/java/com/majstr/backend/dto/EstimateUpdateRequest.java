package com.majstr.backend.dto;

import com.majstr.backend.entity.EstimateStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * {@code deposit_amount} editing was removed from this request (payments-economy-portal
 * iteration) — money now lives on {@code project_payment}, object-level, managed from the
 * Economy tab. {@code Estimate.depositAmount} stays in the schema as a frozen legacy value for
 * estimates created before the move; nothing writes to it anymore.
 */
public record EstimateUpdateRequest(
        @NotNull EstimateStatus status,
        LocalDate validUntil,
        @Size(max = 4000) String notes,
        @Size(max = 255) String name
) {}
