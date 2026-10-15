package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * A public calculator's answer: the buying lines exactly as the app's «Матеріали» screen builds
 * them (quantity, unit, packages and the per-position explanation in {@code sources}), the
 * allowance applied, and the questions still unanswered — {@code PERIMETER} when a ceiling's
 * perimeter was not given.
 */
public record PublicMaterialCalcResponse(
        List<CalculatedMaterialLine> materials,
        BigDecimal wastePercent,
        List<String> missing
) {}
