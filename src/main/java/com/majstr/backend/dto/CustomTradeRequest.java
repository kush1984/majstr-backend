package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CustomTradeRequest(
        @NotBlank @Size(max = 100) String name
) {}
