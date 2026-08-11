package com.majstr.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Edit an already-recorded receipt — amount/date/label only; which stage it closes is fixed at
 *  creation (re-linking would re-open the whole overflow question, deliberately not supported). */
public record PaymentReceiptEditRequest(
        @NotNull @DecimalMin(value = "0.01") @DecimalMax("100000000") BigDecimal amount,
        @NotNull LocalDate receivedAt,
        @Size(max = 255) String label
) {}
