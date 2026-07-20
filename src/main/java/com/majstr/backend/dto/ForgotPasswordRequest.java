package com.majstr.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request a password-reset link. The response is always neutral (anti-enumeration). */
public record ForgotPasswordRequest(
        @NotBlank @Email @Size(max = 255) String email
) {}
