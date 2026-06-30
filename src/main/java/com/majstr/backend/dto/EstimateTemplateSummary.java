package com.majstr.backend.dto;

import com.majstr.backend.entity.Trade;

import java.util.UUID;

/**
 * One row in the template picker / "My templates" list. {@code isDefault}
 * separates the 88 system templates from the master's own; {@code itemCount}
 * is the "N позицій" hint (filled from a single grouped count, no N+1).
 */
public record EstimateTemplateSummary(
        UUID id,
        String name,
        Trade trade,
        boolean isDefault,
        int itemCount
) {}
