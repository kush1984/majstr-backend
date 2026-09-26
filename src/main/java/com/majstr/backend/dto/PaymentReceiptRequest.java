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
        PaymentOverflowResolution resolution,
        /**
         * Money the client paid BACK for material the master laid out (V135) — not payment for work.
         *
         * <p>«Отримано» still counts every hryvnia that arrived, but since review B-65 this tick is
         * load-bearing on the OBJECT too: a refund settles the materials axis and pays for no work,
         * so it never moves «Залишилось». Defaults false, so no existing caller means anything
         * different by omitting it.</p>
         */
        boolean materialRefund
) {}
