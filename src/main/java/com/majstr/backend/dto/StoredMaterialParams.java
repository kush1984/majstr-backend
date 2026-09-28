package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * What the master has already answered on one estimate's calculator (V142) — the perimeter, and the
 * розгортка / thickness of each position that was asked for one.
 *
 * <p>Every value is positive: an unanswered question has no entry at all, because the calculation
 * reads a non-positive parameter as missing and «not answered» may only have one spelling.</p>
 */
public record StoredMaterialParams(
        BigDecimal perimeter,
        Map<UUID, BigDecimal> sections,
        Map<UUID, BigDecimal> thicknesses
) {
    public static final StoredMaterialParams EMPTY =
            new StoredMaterialParams(null, Map.of(), Map.of());
}
