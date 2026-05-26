package com.majstr.backend.service;

import com.majstr.backend.entity.Unit;

import java.util.Map;

/**
 * Human-readable Ukrainian labels for {@link Unit} enum values. Used by
 * server-side renderers (PDF, future emails). The REST API itself returns
 * raw enum codes — clients are free to translate them their own way.
 */
public final class UnitLabel {

    private static final Map<Unit, String> UA = Map.of(
            Unit.M2,    "м²",
            Unit.M,     "м",
            Unit.PIECE, "шт",
            Unit.KG,    "кг",
            Unit.HOUR,  "год",
            Unit.SET,   "компл"
    );

    private UnitLabel() {}

    public static String ua(Unit unit) {
        return UA.getOrDefault(unit, unit.name());
    }
}
