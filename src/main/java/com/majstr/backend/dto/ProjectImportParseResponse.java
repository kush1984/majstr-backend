package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * One parsed documentation file → a DRAFT the master reviews. Nothing is
 * persisted; the PWA merges several files' drafts (room schedule + measure
 * plan + coverings) by room number/name before the review screen. The LLM
 * only transcribes what is printed — every geometric result is computed by
 * our code, and a missing value is {@code null}, never a guess.
 */
public record ProjectImportParseResponse(
        List<Floor> floors,
        List<Covering> coverings,
        /** «Загальна площа» from the schedule footer — the cross-check anchor. */
        BigDecimal totalAreaM2,
        /** Absolute ceiling height per floor label, mm; relative drops are never counted. */
        Map<String, BigDecimal> ceilingHeightsMm,
        List<String> warnings
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
            String note
    ) {}

    public record Opening(
            String kind,
            BigDecimal wMm,
            BigDecimal hMm,
            BigDecimal sillMm,
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
