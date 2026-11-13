package com.majstr.backend.dto;

import com.majstr.backend.entity.Unit;

import java.util.List;
import java.util.UUID;

/**
 * How the calculator read a line whose own name matches no norm (V204, «рахувати як»). Nothing here is
 * silent: a line counted by a rule or by the master's choice says what it is counted as, a line counted
 * as nothing says so, and an unread one comes with the closest library positions to pick from.
 *
 * @param kind        RULE — read by a rule (a tile format); MASTER — the master's own «рахувати як»;
 *                    NO_MATERIALS — he said it consumes nothing; UNMATCHED — nothing known, still asked
 * @param asKey       the library position counted as; null for NO_MATERIALS and UNMATCHED
 * @param suggestions the closest library positions of the same unit, best first (UNMATCHED only)
 */
public record PositionMatch(
        UUID estimateItemId,
        String name,
        Unit unit,
        Kind kind,
        String asKey,
        String asName,
        List<NormPosition> suggestions
) {
    public enum Kind { RULE, MASTER, NO_MATERIALS, UNMATCHED }
}
