package com.majstr.backend.service;

import com.majstr.backend.entity.Unit;

import java.util.Map;

import static java.util.Map.entry;

/**
 * Human-readable Ukrainian labels for {@link Unit} enum values. Used by
 * server-side renderers (PDF, future emails). The REST API itself returns
 * raw enum codes — clients are free to translate them their own way.
 */
public final class UnitLabel {

    private static final Map<Unit, String> UA = Map.ofEntries(
            entry(Unit.M2,           "м²"),
            entry(Unit.M,            "м"),
            entry(Unit.LINEAR_METER, "м.п."),
            entry(Unit.PIECE,        "шт"),
            entry(Unit.KG,           "кг"),
            entry(Unit.HOUR,         "год"),
            entry(Unit.SET,          "компл"),
            entry(Unit.M3,           "м³"),
            entry(Unit.T,            "т"),
            entry(Unit.POINT,        "точка"),
            entry(Unit.PERCENT,      "%")
    );

    private UnitLabel() {}

    public static String ua(Unit unit) {
        return UA.getOrDefault(unit, unit.name());
    }
}
