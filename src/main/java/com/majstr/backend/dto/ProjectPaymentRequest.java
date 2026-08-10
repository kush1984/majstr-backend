package com.majstr.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Create/update a payment row (manual "+ Платіж", or editing one from the split preview).
 * {@code paidAmount}/{@code paidAt} let the master record a payment as already received in
 * the same call (e.g. entering a завдаток the client already paid) — "mark received" from the
 * PWA is just a PATCH with these two fields set, no separate endpoint.
 */
public record ProjectPaymentRequest(
        @NotNull @DecimalMin(value = "0.0") @DecimalMax("100000000") BigDecimal amount,
        LocalDate dueDate,
        @Size(max = 255) String nextStage,
        @NotBlank @Size(max = 255) String purpose,
        @DecimalMin(value = "0.0") @DecimalMax("100000000") BigDecimal paidAmount,
        Instant paidAt
) {}
