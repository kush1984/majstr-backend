package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignRequest(
        @NotBlank @Size(max = 255) String clientName,
        @NotBlank @Size(max = 50) String clientPhone
) {}
