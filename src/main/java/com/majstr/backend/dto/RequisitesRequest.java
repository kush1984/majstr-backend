package com.majstr.backend.dto;

import com.majstr.backend.entity.ActNumberFormat;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * «Налаштування» (V217): the document requisites (acts iteration, V103) and where a client pays.
 * A full PUT of its own — blank clears — kept apart from {@link ProfileUpdateRequest} so the two
 * forms can never blank each other's fields.
 *
 * <p>The document numbers (РНОКПП length, …) stay warnings in the PWA, never blocked here: a master
 * may save a half-filled form. The two payout fields are the exception, because a client sends money
 * to them: a card number must pass the Luhn check and a link must be https.</p>
 */
public record RequisitesRequest(
        @Size(max = 255) String legalName,
        @Size(max = 20) String taxId,
        @Size(max = 512) String legalAddress,
        @Size(max = 64) String iban,
        @Size(max = 255) String bankName,
        Boolean vatPayer,
        @Size(max = 20) String vatId,
        Short taxGroup,
        BigDecimal taxRate,
        @Size(max = 120) String docCity,
        ActNumberFormat actNumberFormat,
        /** Any bank's card; spaces allowed, stored as digits. */
        @Size(max = 30) String payoutCard,
        /** Any https payment page. */
        @Size(max = 500) String payoutLink
) {}
