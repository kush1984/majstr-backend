package com.majstr.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Create/update a PLAN payment stage (manual "+ Запланований", or editing a split preview row).
 * Pure plan — no fact fields. Since the payments PLAN/FACT split (V100) "mark received" is no
 * longer a PATCH on this row; it goes through {@link PaymentReceiptRequest} instead.
 */
public record ProjectPaymentRequest(
        @NotNull @DecimalMin(value = "0.0") @DecimalMax("100000000") BigDecimal amount,
        LocalDate dueDate,
        @Size(max = 255) String nextStage,
        @NotBlank @Size(max = 255) String purpose
) {}
