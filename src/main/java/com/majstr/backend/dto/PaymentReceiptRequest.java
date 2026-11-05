package com.majstr.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Register a received payment ("Отриманий платіж"); {@code label} is required. The plan-era
 * {@code planPaymentId}/{@code resolution} are gone — an app build still sending them is not
 * refused, the fields are simply not read.
 */
public record PaymentReceiptRequest(
        @Size(max = 255) String label,
        @NotNull @DecimalMin(value = "0.01") @DecimalMax("100000000")
        @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @NotNull LocalDate receivedAt,
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
