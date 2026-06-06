package com.majstr.backend.dto;

import com.majstr.backend.entity.Trade;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * Contractor profile edit. {@code email} is optional and honoured **only while
 * the account is unverified** (a contractor may fix a typo from registration);
 * once verified the email is locked and a different value is ignored, the rest
 * of the profile still saving.
 */
public record ProfileUpdateRequest(
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Size(max = 50) String phone,
        @NotBlank @Size(max = 255) String companyName,
        @NotEmpty(message = "at least one trade is required") Set<@NotNull Trade> trades,
        @Email @Size(max = 255) String email
) {}
