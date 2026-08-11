package com.majstr.backend.dto;

import com.majstr.backend.entity.PaymentOverflowResolution;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Register a received payment ("Отриманий платіж"). {@code planPaymentId} null means "Своє" — an
 * unplanned receipt, then {@code label} is required and must not collide with any plan stage's
 * purpose. {@code resolution} is only read when the amount exceeds the targeted stage's remaining
 * balance; the PWA computes the overflow itself (it already holds the summary) and resubmits with
 * the master's choice — the backend re-derives the same overflow independently before trusting it.
 */
public record PaymentReceiptRequest(
        UUID planPaymentId,
        @Size(max = 255) String label,
        @NotNull @DecimalMin(value = "0.01") @DecimalMax("100000000") BigDecimal amount,
        @NotNull LocalDate receivedAt,
        PaymentOverflowResolution resolution
) {}
