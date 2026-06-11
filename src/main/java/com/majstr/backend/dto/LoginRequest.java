package com.majstr.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Email String email,
        // Mirrors the register cap. Without it an oversized password is a cheap
        // CPU attack — BCrypt cost scales with input length.
        @NotBlank @Size(max = 100) String password
) {}
