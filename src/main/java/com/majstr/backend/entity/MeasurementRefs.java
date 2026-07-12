package com.majstr.backend.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Encodes an estimate line's measurement selection (a list of measurement_item ids) into
 * the {@code estimate_items.measurement_refs} column and back — a plain comma-separated
 * string (no JSON dependency). Blank / empty → null column. Unparseable ids are skipped,
 * so a ref to a deleted element is silently ignored (never a hard failure).
 */
public final class MeasurementRefs {

    private MeasurementRefs() {}

    public static String format(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    public static List<UUID> parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        List<UUID> out = new ArrayList<>();
        for (String part : stored.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            try {
                out.add(UUID.fromString(p));
            } catch (IllegalArgumentException ignore) {
                // a stray / malformed id — skip it
            }
        }
        return out;
    }
}
