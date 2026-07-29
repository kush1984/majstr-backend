package com.majstr.backend.service.measurement;

import com.majstr.backend.dto.MeasurementsResponse;
import com.majstr.backend.dto.ProjectImportCommitRequest;
import com.majstr.backend.dto.ProjectImportParseResponse;
import com.majstr.backend.exception.AiExtractionException;
import com.majstr.backend.exception.CatalogImportException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.service.ProjectService;
import com.majstr.backend.service.ai.AiInput;
import com.majstr.backend.service.ai.JsonExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Import a designer's PROJECT DOCUMENTATION (обмірний план / експлікація /
 * специфікація покриттів — PDF sheets or photos) into measurement rooms.
 *
 * <p>The reliability core: these PDFs usually carry a TEXT LAYER, so the tables
 * are extracted exactly with pdfbox and the LLM only STRUCTURES text (cheap,
 * stable, precise). Vision (the native PDF/image block) is the fallback for
 * scans and photos only. Either way the model transcribes what is printed —
 * geometry (walls = perimeter × height − openings, …) is computed by our code
 * on the review screen and re-computed by the server on commit.</p>
 *
 * <p>Classification (file type + floor out of the FILENAME) happens client-side
 * before anything is uploaded — the master's 45-file archive never leaves the
 * phone; only the ~5 useful sheets are posted here, one per call. Files are
 * parsed and discarded, never stored.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectImportService {

    /** What the (client-side) classifier decided this file is — picks the prompt. */
    public enum Kind { ROOM_SCHEDULE, PLAN_MEASURE, COVERINGS }

    static final int MAX_BYTES = 15 * 1024 * 1024;
    /** The PWA splits a bound set page-by-page before upload, so this only guards direct
     *  API callers; matches the client's per-run selection cap. */
    static final int MAX_PDF_PAGES = 10;
    /** Below this the "text layer" is just a stamp/title — not a table worth structuring. */
    static final int MIN_TEXT_CHARS = 150;

    private final FeatureGuard featureGuard;
    private final ProjectService projectService;
    /** Whichever provider `app.ai.provider` selected — this flow does not care which. */
    private final JsonExtractor extractor;
    private final MeasurementService measurementService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    // ---- parse ----------------------------------------------------------------

    public ProjectImportParseResponse parse(UUID ownerId, UUID objectId, Kind kind,
                                            String filename, String contentType, byte[] bytes) {
        featureGuard.requireFeature(loadUser(ownerId), Feature.PROJECT_IMPORT);
        projectService.loadOwned(objectId, ownerId); // existence + ownership (404 / 403)

        if (bytes == null || bytes.length == 0) {
            throw new CatalogImportException("error.import.empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new CatalogImportException("error.import.file-too-large");
        }

        String json;
        if (isPdf(bytes)) {
            // A PLAN or a ROOM SCHEDULE always goes as the native document block: the model
            // then sees the rendered page, not a flattened text stream. The plan needs it for
            // geometry (coordinate-jumbled in raw text); the schedule needs it because a
            // designer's table often typesets a room's NAME away from its row, so text order
            // silently mis-pairs names with numbers. Coverings stay on the cheap text path —
            // a plain table that creates nothing anyway.
            if (kind == Kind.PLAN_MEASURE || kind == Kind.ROOM_SCHEDULE) {
                pageGuard(bytes);
                json = extractor.requestJson(
                        AiInput.pdf(bytes, instruction(kind)),
                        systemPrompt(kind), SCHEMA);
            } else {
                String text = pdfText(bytes);
                if (text != null && text.trim().length() >= MIN_TEXT_CHARS) {
                    // The accurate path: exact printed figures, no vision involved.
                    json = extractor.requestJson(textContent(kind, text), systemPrompt(kind), SCHEMA);
                } else {
                    json = visionPdf(kind, bytes);
                }
            }
        } else {
            String mediaType = imageMediaType(filename, contentType);
            if (mediaType == null) {
                throw new CatalogImportException("error.import.unsupported");
            }
            json = visionImage(kind, mediaType, bytes);
        }
        return toReview(json);
    }

    // ---- commit ---------------------------------------------------------------

    public MeasurementsResponse commit(UUID ownerId, UUID objectId, ProjectImportCommitRequest req) {
        featureGuard.requireFeature(loadUser(ownerId), Feature.PROJECT_IMPORT);
        MeasurementsResponse tree = measurementService.createImported(objectId, ownerId, req.rooms());
        int items = req.rooms().stream().mapToInt(r -> r.items().size()).sum();
        log.info("Project import for {} → object {} (+{} rooms, +{} elements)",
                ownerId, objectId, req.rooms().size(), items);
        return tree;
    }

    // ---- recognition paths -----------------------------------------------------

    private String visionPdf(Kind kind, byte[] bytes) {
        // Only non-plan scans reach here (a plan PDF short-circuits to the document
        // block above) — a single call suffices.
        return extractor.requestJson(
                AiInput.pdf(bytes, instruction(kind)),
                systemPrompt(kind), SCHEMA);
    }

    private String visionImage(Kind kind, String mediaType, byte[] bytes) {
        if (kind != Kind.PLAN_MEASURE) {
            return extractor.requestJson(
                    AiInput.image(mediaType, bytes, instruction(kind)),
                    systemPrompt(kind), SCHEMA);
        }
        return twoPass(instr -> extractor.requestJson(
                AiInput.image(mediaType, bytes, instr), systemPrompt(kind), SCHEMA));
    }

    /**
     * A PHOTO of a measure plan (no text anchor) runs TWO PASSES — the cure for
     * "one run finds every room, the next finds one": a short, stable inventory
     * first, then one details call anchored to that inventory. A PDF plan page
     * doesn't need this: its printed rooms table anchors the single call.
     */
    private String twoPass(java.util.function.Function<String, String> call) {
        String inventoryJson = call.apply(INVENTORY_INSTRUCTION);
        List<String> names = inventoryRoomNames(inventoryJson);
        if (names.isEmpty()) {
            return inventoryJson;
        }
        String detailJson = call.apply(
                "Room inventory from a first pass over this sheet: " + String.join("; ", names) + ".\n"
                        + "For EVERY room in that inventory extract the details per the system prompt. "
                        + "Do not drop any room from the inventory; do not invent new ones unless clearly present.");
        return mergeInventoryIntoDetails(inventoryJson, detailJson);
    }

    private List<AiInput> textContent(Kind kind, String text) {
        return AiInput.text(instruction(kind) + "\n\n--- EXTRACTED PDF TEXT ---\n" + text);
    }

    // ---- pdf helpers -----------------------------------------------------------

    static boolean isPdf(byte[] bytes) {
        return bytes.length > 4
                && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    /** Page-count cap for a document-block upload (the PWA splits sets page-by-page). */
    private void pageGuard(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            if (doc.getNumberOfPages() > MAX_PDF_PAGES) {
                throw new CatalogImportException("error.import.too-many-pages");
            }
        } catch (CatalogImportException e) {
            throw e;
        } catch (Exception e) {
            log.warn("PDF page-count check failed ({}), letting the model try", e.getMessage());
        }
    }

    /** Text layer of the whole file, or null when unreadable (encrypted/corrupt → vision). */
    private String pdfText(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            if (doc.getNumberOfPages() > MAX_PDF_PAGES) {
                throw new CatalogImportException("error.import.too-many-pages");
            }
            return new PDFTextStripper().getText(doc);
        } catch (CatalogImportException e) {
            throw e;
        } catch (Exception e) {
            log.warn("PDF text extraction failed ({}), falling back to vision", e.getMessage());
            return null;
        }
    }

    private static String imageMediaType(String filename, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.equals("image/jpeg") || ct.equals("image/jpg")) return "image/jpeg";
        if (ct.equals("image/png")) return "image/png";
        if (ct.equals("image/webp")) return "image/webp";
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".webp")) return "image/webp";
        return null;
    }

    // ---- LLM JSON → review DTO -------------------------------------------------

    @SuppressWarnings("unchecked")
    private ProjectImportParseResponse toReview(String json) {
        Map<String, Object> root = readMap(json);

        List<ProjectImportParseResponse.Floor> floors = new ArrayList<>();
        for (Object fo : asList(root.get("floors"))) {
            if (!(fo instanceof Map<?, ?> fm)) continue;
            List<ProjectImportParseResponse.Room> rooms = new ArrayList<>();
            for (Object ro : asList(fm.get("rooms"))) {
                if (!(ro instanceof Map<?, ?> rm)) continue;
                rooms.add(room((Map<String, Object>) rm));
            }
            List<String> onSheet = new ArrayList<>();
            for (Object o : asList(fm.get("roomsOnThisSheet"))) {
                String s = str(o);
                if (s != null) onSheet.add(s);
            }
            floors.add(new ProjectImportParseResponse.Floor(str(fm.get("floor")), onSheet, rooms));
        }

        List<ProjectImportParseResponse.Covering> coverings = new ArrayList<>();
        for (Object co : asList(root.get("coverings"))) {
            if (!(co instanceof Map<?, ?> cm)) continue;
            String name = str(cm.get("name"));
            BigDecimal qty = positive(cm.get("qty"));
            if (name == null || qty == null) continue;
            String unit = "LINEAR_METER".equalsIgnoreCase(str(cm.get("unit"))) ? "LINEAR_METER" : "M2";
            coverings.add(new ProjectImportParseResponse.Covering(name, str(cm.get("kind")), qty, unit));
        }

        BigDecimal totalArea = null;
        if (root.get("totals") instanceof Map<?, ?> tm) {
            totalArea = positive(tm.get("totalAreaM2"));
        }

        Map<String, BigDecimal> heights = new LinkedHashMap<>();
        for (Object ho : asList(root.get("ceilingHeights"))) {
            if (!(ho instanceof Map<?, ?> hm)) continue;
            String floor = str(hm.get("floor"));
            BigDecimal h = positive(hm.get("heightMm"));
            if (floor != null && h != null) heights.put(floor, h);
        }

        List<String> warnings = new ArrayList<>();
        for (Object w : asList(root.get("warnings"))) {
            String s = str(w);
            if (s != null) warnings.add(s);
        }
        return new ProjectImportParseResponse(floors, coverings, totalArea, heights, warnings);
    }

    @SuppressWarnings("unchecked")
    private ProjectImportParseResponse.Room room(Map<String, Object> rm) {
        List<ProjectImportParseResponse.Opening> openings = new ArrayList<>();
        for (Object oo : asList(rm.get("openings"))) {
            if (!(oo instanceof Map<?, ?> om)) continue;
            BigDecimal w = positive(om.get("wMm"));
            BigDecimal h = positive(om.get("hMm"));
            if (w == null || h == null) continue; // an opening without both sizes can't subtract
            boolean door = "двері".equalsIgnoreCase(str(om.get("kind")));
            // A door always reaches the floor; otherwise honour the model's flag.
            boolean toFloor = door || bool(om.get("toFloor"));
            openings.add(new ProjectImportParseResponse.Opening(
                    door ? "двері" : "вікно",
                    w, h, positive(om.get("sillMm")), toFloor, str(om.get("note"))));
        }
        List<BigDecimal> segments = new ArrayList<>();
        for (Object so : asList(rm.get("wallSegmentsMm"))) {
            BigDecimal v = positive(so);
            if (v != null) segments.add(v);
        }
        BigDecimal area = positive(rm.get("areaM2"));
        BigDecimal perimeter = positive(rm.get("perimeterMm"));
        // Sentinel discipline: a zeroed/absent value means "not printed" — force the row
        // to low confidence so the review screen highlights it instead of trusting it.
        String confidence = conf(rm.get("confidence"));
        if (area == null) confidence = "low";
        return new ProjectImportParseResponse.Room(
                str(rm.get("number")), str(rm.get("name")), area, perimeter,
                segments.isEmpty() ? null : segments,
                positive(rm.get("widthMm")), positive(rm.get("lengthMm")),
                positive(rm.get("cutWidthMm")), positive(rm.get("cutDepthMm")),
                positive(rm.get("ceilingHmm")),
                openings, confidence, str(rm.get("note")));
    }

    // ---- two-pass merge --------------------------------------------------------

    /** Room labels ("№4 Спальня") from the inventory pass — the anchor list for pass 2. */
    private List<String> inventoryRoomNames(String inventoryJson) {
        List<String> names = new ArrayList<>();
        ProjectImportParseResponse inv = toReview(inventoryJson);
        for (var floor : inv.floors()) {
            for (var room : floor.rooms()) {
                String label = (room.number() != null ? "№" + room.number() + " " : "")
                        + (room.name() != null ? room.name() : "");
                if (!label.isBlank()) names.add(label.trim());
            }
        }
        return names;
    }

    /** Pass 2 wins on details; a room pass 2 lost is re-added from pass 1 with a warning. */
    private String mergeInventoryIntoDetails(String inventoryJson, String detailJson) {
        try {
            ProjectImportParseResponse inv = toReview(inventoryJson);
            ProjectImportParseResponse det = toReview(detailJson);
            List<String> detKeys = new ArrayList<>();
            for (var f : det.floors()) {
                for (var r : f.rooms()) detKeys.add(roomKey(r));
            }
            boolean lost = false;
            for (var f : inv.floors()) {
                for (var r : f.rooms()) {
                    if (!detKeys.contains(roomKey(r))) lost = true;
                }
            }
            if (!lost) {
                return detailJson;
            }
            // Rebuild: details as-is + the lost inventory rooms appended to their floor.
            Map<String, Object> root = readMap(detailJson);
            log.info("Project import pass 2 lost rooms — re-adding from inventory");
            Map<String, Object> merged = new LinkedHashMap<>(root);
            merged.put("floors", mergedFloors(inv, det));
            List<Object> warnings = new ArrayList<>(asList(root.get("warnings")));
            warnings.add("Частину кімнат відновлено з першого проходу — перевірте деталі");
            merged.put("warnings", warnings);
            return objectMapper.writeValueAsString(merged);
        } catch (Exception e) {
            log.warn("Two-pass merge failed ({}), using the details pass as-is", e.getMessage());
            return detailJson;
        }
    }

    private List<Map<String, Object>> mergedFloors(ProjectImportParseResponse inv,
                                                   ProjectImportParseResponse det) {
        List<String> detKeys = new ArrayList<>();
        for (var f : det.floors()) {
            for (var r : f.rooms()) detKeys.add(roomKey(r));
        }
        // Serialize back through plain maps so the merged JSON matches the schema shape.
        List<Map<String, Object>> floors = new ArrayList<>();
        for (var f : det.floors()) {
            floors.add(floorMap(f));
        }
        for (var f : inv.floors()) {
            List<ProjectImportParseResponse.Room> lost = f.rooms().stream()
                    .filter(r -> !detKeys.contains(roomKey(r)))
                    .map(r -> new ProjectImportParseResponse.Room(
                            r.number(), r.name(), r.areaM2(), r.perimeterMm(), r.wallSegmentsMm(),
                            r.widthMm(), r.lengthMm(), r.cutWidthMm(), r.cutDepthMm(), r.ceilingHmm(),
                            r.openings(), "low", "відновлено з інвентарного проходу"))
                    .toList();
            if (lost.isEmpty()) continue;
            Map<String, Object> target = floors.stream()
                    .filter(fm -> java.util.Objects.equals(fm.get("floor"), f.floor() == null ? "" : f.floor()))
                    .findFirst().orElse(null);
            if (target == null) {
                target = new LinkedHashMap<>();
                target.put("floor", f.floor() == null ? "" : f.floor());
                target.put("roomsOnThisSheet", f.roomsOnThisSheet());
                target.put("rooms", new ArrayList<>());
                floors.add(target);
            }
            @SuppressWarnings("unchecked")
            List<Object> rooms = (List<Object>) target.get("rooms");
            for (var r : lost) rooms.add(roomMap(r));
        }
        return floors;
    }

    private static String roomKey(ProjectImportParseResponse.Room r) {
        String number = r.number() == null ? "" : r.number().trim();
        if (!number.isEmpty()) return "#" + number;
        return (r.name() == null ? "" : r.name().trim().toLowerCase(Locale.ROOT));
    }

    private static Map<String, Object> floorMap(ProjectImportParseResponse.Floor f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("floor", f.floor() == null ? "" : f.floor());
        m.put("roomsOnThisSheet", f.roomsOnThisSheet());
        List<Object> rooms = new ArrayList<>();
        for (var r : f.rooms()) rooms.add(roomMap(r));
        m.put("rooms", rooms);
        return m;
    }

    private static Map<String, Object> roomMap(ProjectImportParseResponse.Room r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("number", r.number() == null ? "" : r.number());
        m.put("name", r.name() == null ? "" : r.name());
        m.put("areaM2", r.areaM2() == null ? 0 : r.areaM2());
        m.put("perimeterMm", r.perimeterMm() == null ? 0 : r.perimeterMm());
        m.put("wallSegmentsMm", r.wallSegmentsMm() == null ? List.of() : r.wallSegmentsMm());
        m.put("widthMm", r.widthMm() == null ? 0 : r.widthMm());
        m.put("lengthMm", r.lengthMm() == null ? 0 : r.lengthMm());
        m.put("cutWidthMm", r.cutWidthMm() == null ? 0 : r.cutWidthMm());
        m.put("cutDepthMm", r.cutDepthMm() == null ? 0 : r.cutDepthMm());
        m.put("ceilingHmm", r.ceilingHmm() == null ? 0 : r.ceilingHmm());
        List<Object> openings = new ArrayList<>();
        for (var o : r.openings()) {
            Map<String, Object> om = new LinkedHashMap<>();
            om.put("kind", o.kind());
            om.put("wMm", o.wMm());
            om.put("hMm", o.hMm());
            om.put("sillMm", o.sillMm() == null ? 0 : o.sillMm());
            om.put("toFloor", Boolean.TRUE.equals(o.toFloor()));
            om.put("note", o.note() == null ? "" : o.note());
            openings.add(om);
        }
        m.put("openings", openings);
        m.put("confidence", r.confidence());
        m.put("note", r.note() == null ? "" : r.note());
        return m;
    }

    // ---- small parse helpers ---------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse project import JSON: {}", e.getMessage());
            throw new AiExtractionException("error.ai.unavailable", e);
        }
    }

    private static List<Object> asList(Object o) {
        return o instanceof List<?> l ? new ArrayList<>(l) : List.of();
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    /** Number sentinel: 0 / negative / non-numeric all mean "not printed" → null. */
    private static BigDecimal positive(Object o) {
        if (o instanceof Number n) {
            BigDecimal v = new BigDecimal(n.toString());
            return v.signum() > 0 ? v : null;
        }
        return null;
    }

    /** Lenient boolean read from the parsed JSON (true / "true" → true; anything else false). */
    private static boolean bool(Object o) {
        if (o instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static String conf(Object o) {
        String s = str(o);
        if ("high".equalsIgnoreCase(s)) return "high";
        if ("medium".equalsIgnoreCase(s)) return "medium";
        return "low";
    }

    private User loadUser(UUID ownerId) {
        return userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + ownerId));
    }

    // ---- prompts + schema -------------------------------------------------------

    private static String systemPrompt(Kind kind) {
        return switch (kind) {
            case ROOM_SCHEDULE -> SCHEDULE_PROMPT;
            case PLAN_MEASURE -> PLAN_PROMPT;
            case COVERINGS -> COVERINGS_PROMPT;
        };
    }

    private static String instruction(Kind kind) {
        return switch (kind) {
            case ROOM_SCHEDULE -> "Extract the room schedule (експлікація приміщень) from this sheet.";
            case PLAN_MEASURE -> "Extract the printed room dimensions from this measure plan (обмірний план).";
            case COVERINGS -> "Extract the coverings specification (специфікація покриттів) from this sheet.";
        };
    }

    private static final String INVENTORY_INSTRUCTION =
            "FIRST PASS — INVENTORY ONLY: list EVERY room on this sheet (number, name, area if printed). "
                    + "Leave perimeters, wall segments and openings empty. Completeness over detail.";

    private static final String COMMON_RULES = """

            HARD RULES:
              - This is Ukrainian design-project documentation.
              - READ THE DRAWING VISUALLY. Any embedded text layer may be garbled or absent
                (Cyrillic CID fonts routinely fail to extract) — trust the pixels you SEE on the
                page, never a raw text transcription of it.
              - A PHOTOGRAPHED sheet (shot at an angle) — read ONLY the printed labels, tables and
                symbols. NEVER take a dimension off a photo: perspective distorts distances. A size
                with no printed figure beside it is unknown (0), never an estimate.
              - NUMBERS — two different conventions, do not confuse them:
                • a SPACE is a THOUSANDS group inside a dimension chain, in millimetres:
                  «5 000» = 5000 mm, «1 385» = 1385 mm. NEVER read it as 5, nor as 5.000.
                • a COMMA is the DECIMAL separator: «2,7» = 2.7 m, «12,53» = 12.53 m².
                Ignore a stray superscript «²»/«2» after an area and «мм»/«mm»/«м» unit suffixes —
                report just the number.
              - The sheet's own TITLE / stamp outranks the file name. A file named «обмірний
                план.pdf» may carry «Обмірний план ПІСЛЯ перепланування» on the sheet itself —
                the sheet always wins.
              - Transcribe WHAT IS PRINTED. Never invent a missing value — put 0 (the "unknown"
                sentinel) and set confidence "low" with a short Ukrainian note.
              - Do NOT compute areas, perimeters or lengths INTO THE OUTPUT — the system computes
                geometry from the figures you transcribe. (Multiplying privately to CHECK your own
                reading is expected; just never report a computed number as if it were printed.)
              - Do NOT measure anything off the drawing by eye or scale; only read printed figures.
              - The floor is NOT determined from a table's contents (schedules repeat identically on
                every sheet) — leave floor "" unless the sheet's own title/stamp names it
                («Обмірний план приміщень 1 поверх», «Експлікація приміщень 2 поверх»).
              - roomsOnThisSheet: the room NUMBERS actually drawn/marked on THIS sheet's plan (the
                numbered circles, or the numbers printed beside the stamp). This is what tells which
                rooms belong to this floor when the table itself is identical on every sheet. If the
                sheet has no plan or you can't tell, return an empty list — never guess.
              - Ceiling height is written many ways: «H=2700», «H 2700», «H-2700», the Cyrillic
                «Н=2700», with or without a space and an optional trailing «*» — all mean the room's
                ceiling height in mm. BUT «Нпр=…» (opening height) and «Нпд=…» (window sill) are
                NOT ceiling heights. Only an explicitly printed ABSOLUTE height counts; relative
                drops («опуск від нуля стелі») are NOT a ceiling height — leave 0.
              - Designer remarks like «без запасу на порізку», «уточнити на місці» → add to warnings
                verbatim.
              - Notation you can't be sure of (e.g. Нпд/Нпр next to windows) → transcribe as written
                into the note, do not interpret with your own formula.
              - Return ONLY JSON matching the schema.
            """;

    private static final String SCHEDULE_PROMPT = """
            You read the ROOM SCHEDULE table (експлікація приміщень) of a Ukrainian design project:
            columns are room number, room name, area in m². The footer usually has «Загальна площа»
            — put it into totals.totalAreaM2 (0 if absent).

            Return every row as a room: number (as printed, e.g. "4"), name («Спальня»), areaM2.
            Rooms go under ONE floor entry with floor "" unless the sheet title/stamp names the
            floor. perimeterMm/wallSegmentsMm/openings stay empty — this table has none.

            ⚠️ The table's LAYOUT is what matters: read each row as printed (number + name + area on
            ONE line). A name may be typeset away from its row in the file's text order — trust what
            you SEE on the page, not the raw text order. Never leave a printed name unassigned.
            """ + COMMON_RULES;

    private static final String PLAN_PROMPT = """
            You read a MEASURE PLAN sheet (обмірний план) of a Ukrainian design project. The sheet
            typically carries BOTH the drawing (printed dimension chains in mm, numbered room
            circles, window/door openings, per-room «H=…мм» ceiling heights) AND a rooms table
            («Специфікація приміщень (обміри)» / «Експлікація приміщень»: № + name + area in m²,
            with a «Загальна площа» footer). Extract BOTH in one pass.

            ⚠️ TWO SETS OF PLANS: a project often carries the existing layout («як є», «до
            перепланування») AND the new one («після перепланування»), differing ONLY by the
            sheet's title. The base geometry is the one AFTER remodelling — that is what will be
            finished. State which one you read in the first room's note.

            1. THE TABLE — every row, all of them: number, name, areaM2. Put «Загальна площа»
               into totals.totalAreaM2 (0 if absent). The table is the room inventory — a room
               from the table must appear in the output even if you find no geometry for it.
            2. PER-ROOM GEOMETRY off the drawing, matched by the room's printed number:
               - widthMm × lengthMm: the room's OVERALL gabarits from the dimension chains along
                 its contour. SELF-CHECK BEFORE ANSWERING: widthMm × lengthMm ÷ 1000000 must equal
                 that room's table area to within ±0.3 m². If it doesn't, you misread a chain (most
                 often a «5 000» thousands group) — re-read it ONCE. If it still disagrees, report
                 the figures you ACTUALLY SEE — never bend them to fit the area — set confidence
                 "low" and give the reason in note. Unsure which chain belongs to the room → 0 for
                 both, never a guess.
               - An L-shaped room (a rectangle with one cut-out corner): also cutWidthMm ×
                 cutDepthMm of the cut, read from the chains (0 when not applicable/unsure).
               - ceilingHmm: the ceiling height printed INSIDE that room — «H=…», «H …», «H-…»
                 or the Cyrillic «Н=…» (optional space, optional trailing «*»), in mm. «Нпр=…» is
                 an OPENING's height and «Нпд=…» is a window SILL height — NEVER report them as the
                 ceiling.
               - openings: every window/door on the room's walls: kind "вікно"/"двері",
                 wMm = printed width; hMm = the opening height from «Нпр», «Ндв» (door leaf) or
                 «Нвк» (window) or a doors/windows spec (0 when nowhere printed); sillMm = the
                 «Нпд» window sill height (0 when absent); toFloor = true for doors, open passages
                 and floor-to-ceiling / panoramic windows (they reach the floor), false for an
                 ordinary window on a sill. An interior door SHARED by two rooms belongs to BOTH:
                 list it in each of the two rooms' openings (each room loses that hole from its
                 walls). note = any other marking as written.
                 A doors/windows SPECIFICATION table on the sheet OUTRANKS a figure read off the
                 drawing: when one exists take the sizes from it (confidence "high"); sizes read
                 from the chains alone are "medium".
               - A sloped / mansard ceiling («скоси»), a niche or a ledge → describe it in that
                 room's note. Walls there are approximate and the master must check on site.
               - wallSegmentsMm / perimeterMm: only figures PRINTED as such (never sum or
                 measure yourself; leave 0/empty otherwise).
            """ + COMMON_RULES;

    private static final String COVERINGS_PROMPT = """
            You read a COVERINGS SPECIFICATION (специфікація покриття підлог і стін / покриттів)
            of a Ukrainian design project: a table of finish materials with quantities.

            Return each line as a covering: name (as printed, e.g. «Плитка керамогранітна»),
            kind — one of "підлога", "стіни", "плінтус", "карниз", "молдінг" (closest match),
            qty and unit ("M2" for м², "LINEAR_METER" for м / м.пог / пог.м). Skip lines without a
            numeric quantity. Rooms/floors stay empty for this sheet type.
            """ + COMMON_RULES;

    private static final Map<String, Object> STRING = Map.of("type", "string");
    private static final Map<String, Object> NUMBER = Map.of("type", "number");
    private static final Map<String, Object> BOOL = Map.of("type", "boolean");

    private static Map<String, Object> obj(List<String> required, Map<String, Object> properties) {
        return Map.of("type", "object", "additionalProperties", false,
                "required", required, "properties", properties);
    }

    private static Map<String, Object> arr(Object items) {
        return Map.of("type", "array", "items", items);
    }

    private static final Map<String, Object> OPENING = obj(
            List.of("kind", "wMm", "hMm", "sillMm", "toFloor", "note"),
            Map.of("kind", STRING, "wMm", NUMBER, "hMm", NUMBER, "sillMm", NUMBER,
                    "toFloor", BOOL, "note", STRING));

    private static final Map<String, Object> ROOM = obj(
            List.of("number", "name", "areaM2", "perimeterMm", "wallSegmentsMm",
                    "widthMm", "lengthMm", "cutWidthMm", "cutDepthMm", "ceilingHmm",
                    "openings", "confidence", "note"),
            Map.ofEntries(
                    Map.entry("number", STRING),
                    Map.entry("name", STRING),
                    Map.entry("areaM2", NUMBER),
                    Map.entry("perimeterMm", NUMBER),
                    Map.entry("wallSegmentsMm", arr(NUMBER)),
                    Map.entry("widthMm", NUMBER),
                    Map.entry("lengthMm", NUMBER),
                    Map.entry("cutWidthMm", NUMBER),
                    Map.entry("cutDepthMm", NUMBER),
                    Map.entry("ceilingHmm", NUMBER),
                    Map.entry("openings", arr(OPENING)),
                    Map.entry("confidence", STRING),
                    Map.entry("note", STRING)));

    private static final Map<String, Object> FLOOR = obj(
            List.of("floor", "roomsOnThisSheet", "rooms"),
            Map.of("floor", STRING, "roomsOnThisSheet", arr(STRING), "rooms", arr(ROOM)));

    private static final Map<String, Object> COVERING = obj(
            List.of("name", "kind", "qty", "unit"),
            Map.of("name", STRING, "kind", STRING, "qty", NUMBER, "unit", STRING));

    private static final Map<String, Object> CEILING = obj(
            List.of("floor", "heightMm"),
            Map.of("floor", STRING, "heightMm", NUMBER));

    private static final Map<String, Object> TOTALS = obj(
            List.of("totalAreaM2"), Map.of("totalAreaM2", NUMBER));

    static final Map<String, Object> SCHEMA = obj(
            List.of("floors", "coverings", "totals", "ceilingHeights", "warnings"),
            Map.of(
                    "floors", arr(FLOOR),
                    "coverings", arr(COVERING),
                    "totals", TOTALS,
                    "ceilingHeights", arr(CEILING),
                    "warnings", arr(STRING)));
}
