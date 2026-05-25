package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClientRequest(
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Size(max = 50) String phone,
        @Size(max = 512) String address
) {}
