package com.majstr.backend.service.importer;

import com.majstr.backend.entity.Unit;

import java.util.Locale;
import java.util.Map;

/**
 * Maps free-text unit strings from a master's price list to the {@link Unit} enum
 * via a synonym dictionary. Deterministic and explainable — no AI. An unrecognized
 * token yields {@code null} so the review screen can ask the master to pick one.
 *
 * <p>Matching is on a normalized token (lowercased, trimmed, dots/spaces stripped)
 * so {@code "кв. м"}, {@code "кв.м"} and {@code "кв м"} all collapse to the same key.
 * The order in the map doesn't matter — keys are the already-normalized forms.</p>
 */
public final class UnitNormalizer {

    private UnitNormalizer() {}

    // Keys are pre-normalized (see normalizeToken): lowercase, no dots, no spaces,
    // Cyrillic and Latin variants both listed. "²"/"³" are folded to "2"/"3".
    private static final Map<String, Unit> SYNONYMS = Map.ofEntries(
            // м² — square metre ("м.кв." normalizes to "мкв", "кв.м" to "квм")
            Map.entry("м2", Unit.M2), Map.entry("квм", Unit.M2), Map.entry("мкв", Unit.M2),
            Map.entry("m2", Unit.M2), Map.entry("кв", Unit.M2), Map.entry("sqm", Unit.M2),
            // km — kilometre (cable runs etc.)
            Map.entry("км", Unit.KM), Map.entry("km", Unit.KM),
            // count written as "кількість"/"к-сть"/"к-ть" → pieces (same as шт)
            Map.entry("кількість", Unit.PIECE), Map.entry("кількості", Unit.PIECE),
            Map.entry("ксть", Unit.PIECE), Map.entry("кть", Unit.PIECE),
            // м³ — cubic metre
            Map.entry("м3", Unit.M3), Map.entry("кубм", Unit.M3), Map.entry("m3", Unit.M3),
            Map.entry("куб", Unit.M3),
            // linear metre (running metre) — MUST win over plain "м" for "м.п."/"пог.м"
            Map.entry("мп", Unit.LINEAR_METER), Map.entry("погм", Unit.LINEAR_METER),
            Map.entry("пм", Unit.LINEAR_METER), Map.entry("рм", Unit.LINEAR_METER),
            Map.entry("lm", Unit.LINEAR_METER),
            // plain metre
            Map.entry("м", Unit.M), Map.entry("метр", Unit.M), Map.entry("метрів", Unit.M),
            Map.entry("m", Unit.M),
            // piece
            Map.entry("шт", Unit.PIECE), Map.entry("штук", Unit.PIECE), Map.entry("штука", Unit.PIECE),
            Map.entry("pcs", Unit.PIECE), Map.entry("pc", Unit.PIECE),
            // kilogram
            Map.entry("кг", Unit.KG), Map.entry("kg", Unit.KG),
            // tonne
            Map.entry("т", Unit.T), Map.entry("тонн", Unit.T), Map.entry("тонна", Unit.T),
            Map.entry("тон", Unit.T),
            // hour
            Map.entry("год", Unit.HOUR), Map.entry("ч", Unit.HOUR), Map.entry("час", Unit.HOUR),
            Map.entry("година", Unit.HOUR), Map.entry("hr", Unit.HOUR),
            // set / kit
            Map.entry("компл", Unit.SET), Map.entry("комплект", Unit.SET), Map.entry("кт", Unit.SET),
            Map.entry("набір", Unit.SET), Map.entry("set", Unit.SET),
            // point (e.g. an electrical point)
            Map.entry("точка", Unit.POINT), Map.entry("точок", Unit.POINT), Map.entry("тчк", Unit.POINT),
            // percent
            Map.entry("%", Unit.PERCENT), Map.entry("відс", Unit.PERCENT), Map.entry("процент", Unit.PERCENT)
    );

    /** Best-effort unit for a raw cell value, or {@code null} if unrecognized/blank. */
    public static Unit normalize(String raw) {
        if (raw == null) {
            return null;
        }
        return SYNONYMS.get(normalizeToken(raw));
    }

    /** Lowercase, drop dots/spaces/hyphens/quotes, fold ²→2 and ³→3, keep Cyrillic/Latin. */
    static String normalizeToken(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).trim()
                .replace('²', '2')
                .replace('³', '3')
                .replace("-", ""); // "к-сть" → "ксть", "м-п" → "мп"
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '.' || c == ' ' || c == '\'' || c == '"' || c == ' ') {
                continue; // strip separators so "кв. м" == "квм"
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
