package com.majstr.backend.dto;

import com.majstr.backend.entity.ActNumberFormat;
import com.majstr.backend.entity.Trade;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Contractor profile edit. {@code email} is optional and honoured **only while
 * the account is unverified** (a contractor may fix a typo from registration);
 * once verified the email is locked and a different value is ignored, the rest
 * of the profile still saving.
 *
 * <p>{@code trades} may be EMPTY — a master can drop every system trade and rely entirely on
 * custom trades ({@code user_trade}, edited via {@code /api/profile/custom-trades}), which live
 * outside this request. {@link RegisterRequest} mirrors this: it only requires at least ONE
 * trade overall (system OR a custom name typed on the register form), not specifically a system
 * one.</p>
 */
public record ProfileUpdateRequest(
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Size(max = 50) String phone,
        @NotBlank @Size(max = 255) String companyName,
        @NotNull Set<@NotNull Trade> trades,
        @Email @Size(max = 255) String email,
        // Document requisites (acts iteration) — all OPTIONAL. The number formats (РНОКПП length,
        // etc.) are validated only as a WARNING in the PWA, never blocked here: a master can save a
        // half-filled profile and finish it later. Sizes mirror the columns as the one hard cap.
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
        ActNumberFormat actNumberFormat
) {}
