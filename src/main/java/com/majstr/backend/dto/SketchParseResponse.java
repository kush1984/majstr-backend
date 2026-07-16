package com.majstr.backend.dto;

import com.majstr.backend.entity.MeasurementType;
import com.majstr.backend.entity.Unit;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * Review proposal from a room-sketch photo (LLM vision) — a DRAFT the master verifies
 * against our redrawn schema before it becomes real measurements. Nothing is persisted at
 * parse time. Each element carries our normalized {@code payload} (the same shape the manual
 * editor uses) so the review screen can render the identical diagram beside the photo.
 *
 * @param rooms      recognised rooms, each with measured elements
 * @param unitGuess  the unit the sketch's numbers are in (MM/CM/M) — the review's default
 * @param warnings   sheet-level notes ("scale not given", "part unreadable")
 */
public record SketchParseResponse(
        List<Room> rooms,
        String unitGuess,
        List<String> warnings
) {
    /**
     * @param confidence high/medium/low — low/medium rooms are highlighted for a check
     */
    public record Room(String name, String confidence, List<Item> items) {}

    /**
     * @param payload    normalized entered-data payload (SURFACE carries {@code unit=unitGuess};
     *                   PARTITION/LINEAR are already converted to metres) — feeds the same editor
     * @param result     server-computed metric, or null when the payload is incomplete/invalid
     *                   (an unreadable size) — the element is then flagged for attention
     */
    public record Item(
            MeasurementType type,
            String name,
            Unit unit,
            String confidence,
            String note,
            JsonNode payload,
            BigDecimal result
    ) {}
}
