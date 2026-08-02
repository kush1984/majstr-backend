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
 * @param sheetKind  {@code HAND_DRAWN} or {@code PRINTED_PLAN} — what the sheet turned out to BE,
 *                   which decides whether this reading is the answer at all. A printed plan (a
 *                   designer's sheet or a технічний паспорт) belongs on the project-import
 *                   conveyor: there the printed AREA is reconciled against the gabarits, several
 *                   sheets are merged into one set of rooms, and every room is guaranteed a floor,
 *                   a ceiling and four walls even when nothing was legible. None of that exists on
 *                   this path — it was built for кроки — so a plan read here comes back as chain
 *                   products, with rooms missing their walls and the printed areas discarded. The
 *                   client uses this field to hand the same files to the import flow instead of
 *                   showing a review built on the wrong machinery.
 * @param rooms      recognised rooms, each with measured elements
 * @param unitGuess  the unit the sketch's numbers are in (MM/CM/M) — the review's default
 * @param warnings   sheet-level notes ("scale not given", "part unreadable")
 */
public record SketchParseResponse(
        String sheetKind,
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
