package com.majstr.backend.dto;

import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.util.UUID;

/** One position the calculator could not answer for, and why. */
public record CoverageGap(
        UUID estimateItemId,
        String name,
        Unit unit,
        BigDecimal quantity,
        CoverageGapKind kind
) {}
