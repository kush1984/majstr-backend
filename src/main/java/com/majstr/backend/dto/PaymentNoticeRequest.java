package com.majstr.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * The client's «Я оплатив» off a portal (V217). The amount is what he says he sent — the master
 * records the money himself once it lands; the receipt, if any, comes as the multipart files.
 */
public record PaymentNoticeRequest(
        @NotNull @DecimalMin("0.01") @DecimalMax("999999999.99") @Digits(integer = 9, fraction = 2)
        BigDecimal amount,
        /** Optional: the portal already knows whose object it is. */
        @Size(max = 255) String authorName,
        @Size(max = 2000) String comment
) {}
