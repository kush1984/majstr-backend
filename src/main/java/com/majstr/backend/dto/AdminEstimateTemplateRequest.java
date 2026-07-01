package com.majstr.backend.dto;

import com.majstr.backend.entity.Trade;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin create/update of a default estimate template. {@code trade} is nullable
 * (null = general, shown to every master regardless of their trades).
 */
public record AdminEstimateTemplateRequest(
        @NotBlank @Size(max = 255) String name,
        Trade trade
) {}
