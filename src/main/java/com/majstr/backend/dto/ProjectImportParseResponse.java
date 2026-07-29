package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * One parsed documentation file → a DRAFT the master reviews. Nothing is
 * persisted; the PWA merges several files' drafts (room schedule + measure
 * plan + coverings) by room number/name before the review screen. The LLM
 * only transcribes what is printed — every geometric result is computed by
 * our code, and a value nowhere printed is {@code null}, never a guess.
 *
 * <p>A figure that WAS read but could not be reconciled is a THIRD case, and it must not be
 * thrown away: it comes through with its field named in {@link Room#uncertain()}, so the review
 * screen can show the number and ask the master to check it. Discarding those taught nobody
 * anything — a sheet whose dimensions only half-reconcile is the normal case, not the exception.</p>
 */
public record ProjectImportParseResponse(
        List<Floor> floors,
        List<Covering> coverings,
        /** «Загальна площа» from the schedule footer — the cross-check anchor. */
        BigDecimal totalAreaM2,
        /** Absolute ceiling height per floor label, mm; relative drops are never counted. */
        Map<String, BigDecimal> ceilingHeightsMm,
        List<String> warnings,
        /**
         * What the MODEL says this sheet is, read off the sheet's own stamp («ОБМІРНИЙ ПЛАН»,
         * «ПЛАН ПІДЛОГ»). Our own label is a filename guess made before anything was read, and on
         * real sets it is wrong often enough that the sheet's own answer has to be visible.
         */
        String sheetTitle
) {
    public record Floor(
            String floor,
            /**
             * Room numbers actually MARKED on this sheet. A schedule table is routinely
             * printed identically on every floor's sheet, so the table can't say which
             * floor a room is on — but the numbers drawn on that sheet's plan can.
             */
            List<String> roomsOnThisSheet,
            List<Room> rooms
    ) {}

    public record Room(
            String number,
            String name,
            BigDecimal areaM2,
            BigDecimal perimeterMm,
            List<BigDecimal> wallSegmentsMm,
            /** Overall gabarits off the dimension chains, mm — validated by the client
             *  against the table area (checksum) before they are ever trusted. */
            BigDecimal widthMm,
            BigDecimal lengthMm,
            /** L-shaped room: the cut-out corner's width/depth, mm (0 = not seen). */
            BigDecimal cutWidthMm,
            BigDecimal cutDepthMm,
            /** Per-room ceiling height from the plan's printed «H=…мм» (never Нпр/Нпд). */
            BigDecimal ceilingHmm,
            List<Opening> openings,
            String confidence,
            String note,
            /**
             * Field names of this room whose figures were READ but not confirmed —
             * {@code ["widthMm","ceilingHmm"]}. The value stays; the review screen marks it
             * «перепровірити». This is the alternative to the old sentinel discipline, where an
             * unreconciled chain became 0 and the master got an empty field with no hint that
             * anything had been read at all.
             */
            List<String> uncertain
    ) {}

    public record Opening(
            String kind,
            BigDecimal wMm,
            BigDecimal hMm,
            BigDecimal sillMm,
            /** True when the opening reaches the floor (doors, open passages, panoramic
             *  windows) — it interrupts the skirting board. Wrapper type so an older-shape
             *  payload with no field deserializes as null, not a primitive-null failure. */
            Boolean toFloor,
            String note
    ) {}

    /** A coverings-specification line («Плитка 94,5 м²») — reference data, not tied to rooms. */
    public record Covering(
            String name,
            String kind,
            BigDecimal qty,
            String unit
    ) {}
}
