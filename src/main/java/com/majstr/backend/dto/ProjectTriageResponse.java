package com.majstr.backend.dto;

import java.util.List;

/**
 * What each sheet turned out to be, in the model's own reading of its title block.
 *
 * <p>This replaces a keyword list as the thing that DECIDES what gets read. Keywords worked on the
 * projects they were written from and failed on the next studio: eight Ukrainian patterns, no Russian
 * and no English, and a sheet that matched none of them was never sent at all. A model reading the
 * sheet's own title has no such vocabulary problem, and it costs one cheap text call per set.</p>
 */
public record ProjectTriageResponse(List<Sheet> sheets) {

    /**
     * @param id            echoed from the request, so the client can match rows without ordering
     *                      assumptions
     * @param title         the sheet's own title, as printed — «ОБМІРНИЙ ПЛАН», «Экспликация
     *                      помещений», «02_обмірний план»
     * @param kind          PLAN_MEASURE | ROOM_SCHEDULE | COVERINGS | ELECTRICAL | OTHER — the same
     *                      names the client already uses, so it can drop them straight in
     * @param floor         the floor THIS SHEET is of, from its title block — never from a room name
     * @param version       AFTER | EXISTING | UNKNOWN: a set routinely carries the same plan twice,
     *                      and the after-remodelling one is the flat that will exist
     * @param hasRoomTable  a table of rooms with areas is printed on this sheet
     * @param hasDimensions dimension chains along the walls
     * @param hasOpeningSizes a doors/windows specification, or per-opening sizes
     * @param worthReading  the model's own answer to "would reading this sheet in detail produce
     *                      measurements?" — the recommendation, not a command: the master sees the
     *                      list and the ticks
     * @param note          anything else worth telling the master, in Ukrainian
     */
    public record Sheet(
            String id,
            String title,
            String kind,
            String floor,
            String version,
            boolean hasRoomTable,
            boolean hasDimensions,
            boolean hasOpeningSizes,
            boolean worthReading,
            String note
    ) {}
}
