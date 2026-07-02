package com.majstr.backend.dto;

import com.majstr.backend.entity.Trade;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 100) String password,
        @NotBlank @Size(max = 255) String fullName,
        @NotEmpty(message = "at least one trade is required") Set<@NotNull Trade> trades,
        @NotBlank @Size(max = 50) String phone,
        @NotBlank @Size(max = 255) String companyName,
        // Explicit privacy-policy consent. The PWA blocks submit without the
        // checkbox; this enforces it at the API too (defense in depth).
        @AssertTrue(message = "consent to the privacy policy is required") boolean consent,
        // Optional first-touch attribution — the ?ref= link value (from PWA
        // storage) and/or a typed community promo code. Both resolve to a partner
        // source (ref wins); absent → DIRECT. Never breaks a plain registration.
        @Size(max = 40) String ref,
        @Size(max = 40) String promoCode
) {}
