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
import com.majstr.backend.service.ai.AiExtractors;
import com.majstr.backend.service.ai.AiFlow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * What the client-side classifier GUESSED this sheet is, from a filename or a stamp. It picks
     * the prompt's emphasis and nothing more: every prompt asks for everything the sheet carries,
     * and each one is told the label may be wrong.
     *
     * <p>{@code UNKNOWN} is not a failure — it is the honest answer for a page whose stamp we do
     * not recognise (a raster export with no text layer, Russian wording, a designer's own
     * naming). Those pages used to be dropped before upload; on one real 19-sheet set that meant
     * the entire import did nothing, because not one page matched a known pattern.</p>
     */
    public enum Kind { ROOM_SCHEDULE, PLAN_MEASURE, COVERINGS, UNKNOWN }

    static final int MAX_BYTES = 15 * 1024 * 1024;
    /** The PWA splits a bound set page-by-page before upload, so this only guards direct
     *  API callers; matches the client's per-run selection cap. */
    static final int MAX_PDF_PAGES = 10;
    /** Below this the "text layer" is just a stamp/title — not a table worth structuring. */
    static final int MIN_TEXT_CHARS = 150;

    private final FeatureGuard featureGuard;
    private final ProjectService projectService;
    /** Whichever model `app.ai.flows.project-docs` names — the heaviest flow, five passes deep. */
    private final AiExtractors extractors;
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
            // UNKNOWN goes down the vision path with them, never down the text one: a sheet we
            // could not name is far more likely to be a drawing than a plain table, and a drawing
            // read as flattened text loses the very thing it was sent for.
            if (kind == Kind.PLAN_MEASURE || kind == Kind.ROOM_SCHEDULE || kind == Kind.UNKNOWN) {
                pageGuard(bytes);
                json = withFragmentsIfNeeded(kind, bytes, extractors.forFlow(AiFlow.PROJECT_DOCS).requestJson(
                        AiInput.pdf(bytes, instruction(kind)),
                        systemPrompt(kind), SCHEMA));
            } else {
                String text = pdfText(bytes);
                if (text != null && text.trim().length() >= MIN_TEXT_CHARS) {
                    // The accurate path: exact printed figures, no vision involved.
                    json = extractors.forFlow(AiFlow.PROJECT_DOCS).requestJson(textContent(kind, text), systemPrompt(kind), SCHEMA);
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

    /**
     * A second look at the same sheet, in four overlapping fragments at a resolution we control —
     * but ONLY when the first look came back without geometry.
     *
     * <p>The reason it is conditional is cost: fragments are four more vision calls on the same
     * page. When the whole-page pass already produced gabarits for its rooms, they were legible and
     * there is nothing to gain. When it produced rooms with no dimensions at all — the exact failure
     * the master reported, and the predictable one for 8 pt chains on an A3 sheet squeezed into
     * 1568 px — the fragments are the only way those figures are ever read.</p>
     *
     * <p>Every failure here degrades to the whole-page answer rather than losing it: a broken render
     * returns no fragments, and a fragment call that throws is skipped with a warning.</p>
     */
    private String withFragmentsIfNeeded(Kind kind, byte[] bytes, String wholePageJson) {
        List<String> inventory;
        try {
            if (!needsFragments(kind, wholePageJson)) {
                return wholePageJson;
            }
            inventory = inventoryRoomNames(wholePageJson);
        } catch (Exception e) {
            log.warn("Could not judge whether fragments are needed ({}) — keeping the whole-page pass",
                    e.getMessage());
            return wholePageJson;
        }
        String instruction = instruction(kind) + "\n\nThe whole sheet has already been read at low "
                + "resolution and its rooms are: " + String.join("; ", inventory) + ".\n"
                + "You are now looking at ONE FRAGMENT of that same sheet, enlarged, so that the "
                + "dimension chains are legible. Extract the GEOMETRY that was unreadable before — "
                + "gabarits, ceiling heights, openings — for the rooms visible in this fragment. "
                + "Do not re-list rooms you cannot see here, and do not invent new ones.";
        List<List<AiInput>> fragments = SheetTiler.tiles(bytes, instruction);
        if (fragments.isEmpty()) {
            return wholePageJson;
        }
        List<Map<String, Object>> readings = new ArrayList<>(fragments.size());
        for (List<AiInput> fragment : fragments) {
            try {
                readings.add(readMap(extractors.forFlow(AiFlow.PROJECT_DOCS).requestJson(fragment, systemPrompt(kind), SCHEMA)));
            } catch (Exception e) {
                // One unreadable quarter must not cost the other three.
                log.warn("Fragment pass failed ({}) — continuing with the rest", e.getMessage());
            }
        }
        if (readings.isEmpty()) {
            return wholePageJson;
        }
        try {
            Map<String, Object> merged = SheetMerge.mergeGeometry(readMap(wholePageJson), readings);
            log.info("Project import read {} fragments of one sheet to recover geometry",
                    readings.size());
            return objectMapper.writeValueAsString(merged);
        } catch (Exception e) {
            log.warn("Fragment merge failed ({}) — keeping the whole-page pass", e.getMessage());
            return wholePageJson;
        }
    }

    /**
     * Whether a closer look is worth four more calls.
     *
     * <p>"No room has BOTH gabarits" rather than "some room is missing one": a plan where the model
     * read half the chains is a different situation from one where it read none, and only the second
     * is worth paying again for.</p>
     *
     * <p><b>An EMPTY answer counts.</b> The first version required {@code rooms > 0}, and that quietly
     * disabled the whole mechanism on the sheets that need it most: the Дубляни measure plan carries
     * no rooms table at all — the rooms live in a separate «експлікація» file — so a squeezed
     * whole-page pass returns nothing, the gate read "nothing to improve", and the fragments never
     * ran. Everything came back zero and the tiling looked broken when it had simply never been
     * asked to work.</p>
     *
     * <p>For a sheet nobody classified, the SHEET'S OWN title decides: the model has just told us
     * what it is, and a title page or a drawings index legitimately holds no rooms — going four
     * calls deeper on those would be paying to re-read a cover sheet.</p>
     */
    private boolean needsFragments(Kind kind, String wholePageJson) {
        ProjectImportParseResponse review = toReview(wholePageJson);
        int rooms = 0;
        int withGeometry = 0;
        for (var floor : review.floors()) {
            for (var room : floor.rooms()) {
                rooms++;
                if (room.widthMm() != null && room.lengthMm() != null) withGeometry++;
            }
        }
        boolean drawing = kind == Kind.PLAN_MEASURE || looksLikeAPlan(review.sheetTitle());
        boolean needed = withGeometry == 0 && (rooms > 0 || drawing);
        // Logged either way: "why did the fragments not run" cost a round trip to answer once.
        log.info("Whole-page pass: kind={} sheet='{}' rooms={} withGeometry={} → fragments={}",
                kind, review.sheetTitle(), rooms, withGeometry, needed);
        return needed;
    }

    /** The model's own words for the sheet — «ОБМІРНИЙ ПЛАН», «ПЛАН ПІДЛОГ», «02_обмірний план». */
    private static boolean looksLikeAPlan(String sheetTitle) {
        if (sheetTitle == null) {
            return false;
        }
        String t = sheetTitle.toLowerCase(Locale.ROOT);
        return t.contains("план") || t.contains("обмір") || t.contains("plan");
    }

    private String visionPdf(Kind kind, byte[] bytes) {
        // Only non-plan scans reach here (a plan PDF short-circuits to the document
        // block above) — a single call suffices.
        return extractors.forFlow(AiFlow.PROJECT_DOCS).requestJson(
                AiInput.pdf(bytes, instruction(kind)),
                systemPrompt(kind), SCHEMA);
    }

    private String visionImage(Kind kind, String mediaType, byte[] bytes) {
        if (kind != Kind.PLAN_MEASURE && kind != Kind.UNKNOWN) {
            return extractors.forFlow(AiFlow.PROJECT_DOCS).requestJson(
                    AiInput.image(mediaType, bytes, instruction(kind)),
                    systemPrompt(kind), SCHEMA);
        }
        return twoPass(instr -> extractors.forFlow(AiFlow.PROJECT_DOCS).requestJson(
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
            return dedupeDoubledText(new PDFTextStripper().getText(doc));
        } catch (CatalogImportException e) {
            throw e;
        } catch (Exception e) {
            log.warn("PDF text extraction failed ({}), falling back to vision", e.getMessage());
            return null;
        }
    }

    /**
     * Collapses text that a PDF painted twice over itself, line by line.
     *
     * <p>One studio's files draw every string on top of itself: «02_обмірний план 02_обмірний план»,
     * «ТВ ТВ духовка духовка». On paper it is invisible — the second copy lands exactly on the first
     * — but through the text path it doubles every figure in a specification table, which would
     * double a quantity the master then buys.</p>
     *
     * <p>The repeated unit is a whole text ITEM, so it can be several tokens long — «ТВ ТВ духовка
     * духовка» is three items doubled, not one — and the longest run is collapsed first so a
     * three-word item is not mistaken for three doubled words.</p>
     *
     * <p>This DOES collapse two genuinely equal adjacent figures («800 800» → «800»), and that is
     * acceptable only because of where it runs: the text path serves specification tables, whose
     * rows are name + quantity + unit. Dimension chains, where repeated figures are normal and
     * meaningful, are read on the vision path and never pass through here. Move this call and that
     * reasoning stops holding.</p>
     */
    static String dedupeDoubledText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            if (out.length() > 0) out.append('\n');
            out.append(dedupeLine(line));
        }
        return out.toString();
    }

    private static String dedupeLine(String line) {
        String[] tokens = line.trim().split("\\s+");
        if (tokens.length < 2) {
            return line;
        }
        // The whole line drawn twice — the common case, and the only one that is unambiguous.
        if (tokens.length % 2 == 0 && halvesMatch(tokens)) {
            return String.join(" ", Arrays.copyOfRange(tokens, 0, tokens.length / 2));
        }
        // Otherwise collapse adjacent repeats of a run, longest run first: an item of three words
        // drawn twice must not be mistaken for three separate doubled words.
        List<String> kept = new ArrayList<>(List.of(tokens));
        for (int size = Math.min(6, kept.size() / 2); size >= 1; size--) {
            for (int i = 0; i + 2 * size <= kept.size(); ) {
                if (kept.subList(i, i + size).equals(kept.subList(i + size, i + 2 * size))) {
                    kept.subList(i + size, i + 2 * size).clear();
                } else {
                    i++;
                }
            }
        }
        return String.join(" ", kept);
    }

    private static boolean halvesMatch(String[] tokens) {
        int half = tokens.length / 2;
        for (int i = 0; i < half; i++) {
            if (!tokens[i].equals(tokens[half + i])) return false;
        }
        return true;
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
        return new ProjectImportParseResponse(floors, coverings, totalArea, heights, warnings,
                str(root.get("sheetTitle")));
    }

    @SuppressWarnings("unchecked")
    private ProjectImportParseResponse.Room room(Map<String, Object> rm) {
        List<ProjectImportParseResponse.Opening> openings = new ArrayList<>();
        boolean partialOpening = false;
        for (Object oo : asList(rm.get("openings"))) {
            if (!(oo instanceof Map<?, ?> om)) continue;
            BigDecimal w = positive(om.get("wMm"));
            BigDecimal h = positive(om.get("hMm"));
            if (w == null && h == null) continue; // nothing was read at all
            if (w == null || h == null) {
                // HALF an opening is still worth keeping. This used to be dropped, which is why a
                // sheet marking «Нпр=2200» beside every door produced no openings at all: the
                // heights were printed, the widths had to come off a chain, and one missing figure
                // discarded the pair. The missing side goes as 0 (subtracts nothing, breaks no
                // arithmetic) and the room is flagged so the review asks for it.
                partialOpening = true;
                if (w == null) w = BigDecimal.ZERO;
                if (h == null) h = BigDecimal.ZERO;
            }
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
        List<String> uncertain = new ArrayList<>();
        for (Object uo : asList(rm.get("uncertain"))) {
            String s = str(uo);
            if (s != null && !uncertain.contains(s)) uncertain.add(s);
        }
        BigDecimal width = positive(rm.get("widthMm"));
        BigDecimal length = positive(rm.get("lengthMm"));
        // An area nowhere on the sheet is ordinary (a bare measure plan prints none), so it no
        // longer damns the whole row — it is one unconfirmed FIELD. It only means "check this"
        // when there are also no gabarits to compute it from.
        if (area == null && !uncertain.contains("areaM2")) uncertain.add("areaM2");
        if (partialOpening && !uncertain.contains("openings")) uncertain.add("openings");
        String confidence = conf(rm.get("confidence"));
        if (area == null && (width == null || length == null)) confidence = "low";
        else if (!uncertain.isEmpty() && "high".equals(confidence)) confidence = "medium";
        return new ProjectImportParseResponse.Room(
                str(rm.get("number")), str(rm.get("name")), area, perimeter,
                segments.isEmpty() ? null : segments,
                width, length,
                positive(rm.get("cutWidthMm")), positive(rm.get("cutDepthMm")),
                positive(rm.get("ceilingHmm")),
                openings, confidence, str(rm.get("note")),
                uncertain);
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
                            r.openings(), "low", "відновлено з інвентарного проходу",
                            r.uncertain()))
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
        m.put("uncertain", r.uncertain() == null ? List.of() : r.uncertain());
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
            case UNKNOWN -> GENERIC_PROMPT;
        };
    }

    /**
     * The label travels as a HINT, never as a contract. Measured on four real sets: one 19-sheet
     * project has no measure plan at all, another's areas sit on the floor-finish sheets, a third
     * marks heights only as level marks. A prompt that assumes its label is right extracts nothing
     * from any of them, and the master sees an empty screen with no reason given.
     */
    private static String instruction(Kind kind) {
        String hint = switch (kind) {
            case ROOM_SCHEDULE -> "a room schedule (експлікація приміщень)";
            case PLAN_MEASURE -> "a measure plan (обмірний план)";
            case COVERINGS -> "a coverings specification (специфікація покриттів)";
            case UNKNOWN -> "unclassified — we could not tell from its name or stamp";
        };
        return "This sheet was labelled " + hint + " from its file name — A GUESS, made before "
                + "anything on it was read, and wrong often enough that you must not rely on it. "
                + "Read the sheet's own stamp, report it in sheetTitle, and extract EVERYTHING it "
                + "carries of what the system prompt asks for — whatever kind of sheet it turns "
                + "out to be.";
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
              - NUMBERS — different conventions, do not confuse them:
                • a SPACE is a THOUSANDS group inside a dimension chain, in millimetres:
                  «5 000» = 5000 mm, «1 385» = 1385 mm. NEVER read it as 5, nor as 5.000.
                • a COMMA **or a DOT** is the DECIMAL separator — studios differ and both appear:
                  «2,7» and «2.7» are both 2.7 m; «12,53 м²» and «12.63 m²» are both an area.
                Ignore a stray superscript «²»/«2» after an area and «мм»/«mm»/«м» unit suffixes —
                report just the number.
              - READ THE SHEET'S OWN UNIT LEGEND when it has one («розміри вказані в міліметрах,
                відмітки в метрах») — it tells you which figures are mm and which are metres.
              - The sheet's own TITLE / stamp outranks the file name. A file named «обмірний
                план.pdf» may carry «Обмірний план ПІСЛЯ перепланування» on the sheet itself —
                the sheet always wins.
              - Transcribe WHAT IS PRINTED. Never invent a value that is not on the sheet.
              - THREE CASES, and only the first is 0:
                • nothing printed anywhere for it → 0, and name the field in "uncertain";
                • printed and you are confident → the figure, "uncertain" stays empty for it;
                • printed but you cannot CONFIRM it (a chain you had to interpret, an area that
                  doesn't reconcile, a symbol you had to read through) → STILL REPORT THE FIGURE
                  YOU READ, name the field in "uncertain", say why in note. Do NOT zero it and do
                  NOT bend it to fit. A number the master can check beats an empty field: he is
                  standing in the flat and can measure it in ten seconds — but only if he knows
                  which one to check.
              - "uncertain" holds FIELD NAMES of that room: "areaM2", "widthMm", "lengthMm",
                "perimeterMm", "ceilingHmm", "openings". Empty list = everything reported is solid.
              - Do NOT compute areas, perimeters or lengths INTO THE OUTPUT — the system computes
                geometry from the figures you transcribe. (Multiplying privately to CHECK your own
                reading is expected; just never report a computed number as if it were printed.)
                THE ONE EXCEPTION is converting level marks into heights, above: subtracting two
                printed marks is transcription in another unit, not an estimate.
              - Do NOT measure anything off the drawing by eye or scale; only read printed figures.
              - The floor is NOT determined from a table's contents (schedules repeat identically on
                every sheet) — leave floor "" unless the sheet's own title/stamp names it
                («Обмірний план приміщень 1 поверх», «Експлікація приміщень 2 поверх»).
              - roomsOnThisSheet: the room NUMBERS actually drawn/marked on THIS sheet's plan (the
                numbered circles, or the numbers printed beside the stamp). This is what tells which
                rooms belong to this floor when the table itself is identical on every sheet. If the
                sheet has no plan or you can't tell, return an empty list — never guess.
              - HEIGHTS come in TWO different notations. Recognise BOTH — a studio uses one or the
                other, and finding neither is usually a misread, not an empty sheet:
                (a) DIRECT: «H=2700», «H 2700», «H-2700», the Cyrillic «Н=2700», optional trailing
                    «*» — the room's ceiling height in MILLIMETRES. «Нпр=…» is an opening's height,
                    «Нпд=…» a window sill, «Ндв=…» a door leaf, «Нвк=…» a window — NOT ceilings.
                (b) LEVEL MARKS («відмітки»), usually in METRES with a dot or comma: «відмітка
                    стелі» 2.93, «відмітка підлоги» 0.00, «відмітка верха прорізу» 2.28, «відмітка
                    низа прорізу» 0.82. Convert to mm:
                      ceilingHmm = (ceiling mark − floor mark) × 1000  → 2930
                      an opening's hMm = (top mark − bottom mark) × 1000  → 1460
                      sillMm = (bottom mark − floor mark) × 1000  → 820
                    This subtraction is the ONE arithmetic you are asked to do (see the next rule):
                    it is exact, not an estimate. Say «з відміток» in the note so it is traceable.
                Relative drops («опуск від нуля стелі», «-0,15 від стелі») are NOT ceiling heights.
              - Designer remarks like «без запасу на порізку», «уточнити на місці» → add to warnings
                verbatim.
              - Notation you can't be sure of (e.g. Нпд/Нпр next to windows) → transcribe as written
                into the note, do not interpret with your own formula.
              - Return ONLY JSON matching the schema.
            """;

    /**
     * What to look for on ANY sheet. Shared by every prompt on purpose: a sheet's real content
     * does not follow the label we guessed for it, so no prompt is allowed to gate data off. The
     * per-kind text above this only says where to look FIRST.
     */
    private static final String SHEET_CORE = """

            EXTRACT EVERYTHING BELOW THAT THIS SHEET HAPPENS TO CARRY. Sheets vary enormously
            between studios; absence of one thing says nothing about the others.

            1. ROOM INVENTORY — from whichever of these the sheet has, most trustworthy first:
               (a) a rooms TABLE: «Специфікація приміщень (обміри)», «Експлікація приміщень»,
                   columns № + name + area in m², often with a «Загальна площа» footer → every row
                   becomes a room, and the footer goes to totals.totalAreaM2;
               (b) LABELS printed inside the rooms on the plan — a numbered circle, a name, an area
                   like «12.63 m²» typeset in the room. This is just as valid as a table: many
                   studios print no table at all;
               (c) numbered circles ALONE, with no name and no area.
               A room from the inventory MUST appear in the output even when you find no geometry
               for it. If the sheet has none of (a)(b)(c) it has no rooms — return "floors": [] and
               say what the sheet is in sheetTitle. Never invent rooms to fill the output.
               ⚠️ The table's LAYOUT is what matters: read each row as printed (number + name +
               area on ONE line). A name is often typeset away from its row in the file's text
               order — trust what you SEE, not the text order. Never leave a printed name
               unassigned.
            2. PER-ROOM GEOMETRY off the drawing, matched by the room's printed number/name:
               - widthMm × lengthMm — the room's OVERALL gabarits from the dimension chains along
                 its contour.
                 SELF-CHECK WHEN AN AREA IS KNOWN: widthMm × lengthMm ÷ 1000000 must match that
                 room's area to within ±0.3 m². If it doesn't, you misread a chain (most often a
                 «5 000» thousands group) — re-read it ONCE. If it still disagrees, report the
                 figures you ACTUALLY SEE, name them in "uncertain" and give the reason in note.
                 Never bend a figure to fit the area.
                 WHEN NO AREA IS PRINTED ANYWHERE there is nothing to check against — that is
                 normal on a plain measure plan. Report the chains you read and name widthMm /
                 lengthMm in "uncertain". The system computes the area from them.
               - An L-shaped room (a rectangle with one cut-out corner): also cutWidthMm ×
                 cutDepthMm of the cut, from the chains.
                 ⚠️ THE AREA TELLS YOU WHEN TO LOOK FOR ONE. If widthMm × lengthMm comes out
                 BIGGER than the room's printed area, the room is not a rectangle — a corridor
                 wrapping a corner, a niche, a boxed-in riser. Follow the room's contour on the
                 drawing and read the two chains of the cut-out; the difference between w×l and the
                 printed area is roughly the cut's area, which tells you whether you found the right
                 pair. If you cannot read them, say "cutWidthMm" in "uncertain" and describe the
                 shape in note — do NOT shrink widthMm/lengthMm to make the multiplication fit, and
                 do not drop them: they are the room's bounding box, which is what its WALLS follow.
               - wallSegmentsMm / perimeterMm — only figures PRINTED as such; never sum or measure
                 them yourself.
               - A sloped / mansard ceiling («скоси»), a niche or a ledge → describe it in that
                 room's note. Walls there are approximate and the master must check on site.
            3. HEIGHTS — both notations from the HARD RULES (direct «H=2700» and level marks
               «відмітка стелі 2.93»). Per-room → ceilingHmm. A single height stated for the whole
               floor in the stamp or the notes → ceilingHeights, keyed by the floor label.
            4. OPENINGS — every window and door on a room's walls: kind "вікно"/"двері",
               wMm = printed width, hMm = the opening's height, sillMm = the window sill.
               HOW TO FIND ONE WHEN NOTHING IS LABELLED. Many sheets mark no opening sizes at all —
               the openings are drawn, and their widths are segments of the wall's dimension chain.
               A number on its own cannot tell you which segment that is («800» is a door leaf on
               one wall and a pier on the next), so read the WALL, not the chain:
                 • a DOOR is a gap in the wall's hatching with a leaf arc (a quarter circle) or a
                   sliding/folding symbol across it;
                 • a WINDOW is a gap crossed by 2–4 thin parallel lines;
                 • an OPEN PASSAGE («без дверей», «арка») is a gap with neither an arc nor lines —
                   kind "двері" with toFloor true, since it interrupts the skirting the same way;
                 • the chain then CONFIRMS the width: the sub-pattern «pier — gap — pier»
                   («800 2 874 800») lines up with the drawn gap, and the middle figure is wMm.
               Take a width only when you can see WHICH gap it spans. If you cannot, leave the
               opening out entirely rather than pairing a number with a guess — but if you can see
               the gap and the height is nowhere printed, report the width with hMm 0 and flag it
               (see below): the room is then one tap from complete instead of missing a hole.
               Sources, in order: a doors/windows SPECIFICATION table on this sheet («Д 01», «ДЗ
               02», «В 07» rows with sizes) OUTRANKS everything — take the sizes from it and set
               confidence "high"; then «Нпр»/«Ндв»/«Нвк»/«Нпд» markings; then level marks; sizes
               taken off the chains alone are "medium".
               toFloor = true for doors, open passages and floor-to-ceiling / panoramic windows
               (they reach the floor and interrupt the skirting), false for a window on a sill.
               An interior door SHARED by two rooms belongs to BOTH — list it in each room's
               openings, because each of them loses that hole from its walls.
               ⚠️ REPORT HALF AN OPENING RATHER THAN NONE. These sheets routinely print one
               dimension and not the other — a height beside every door («Нпр=2200») whose width
               only exists as a segment in the wall's dimension chain, or a width in a chain with
               the height nowhere on the sheet. Give the figure you have, put 0 in the other, and
               name "openings" in "uncertain". An opening the master can finish in one tap is worth
               far more than a wall with no hole in it at all.
               note = any other marking, as written.
            5. COVERINGS — when the sheet is a finishes specification («специфікація покриттів»,
               a floor/wall finishes table): each line as name (as printed), kind — one of
               "підлога", "стіни", "плінтус", "карниз", "молдінг" (closest match) — qty, and unit
               ("M2" for м², "LINEAR_METER" for м / м.пог / пог.м). Skip lines with no number.
            6. sheetTitle — this sheet's own title from its stamp, as printed («ОБМІРНИЙ ПЛАН»,
               «ПЛАН ПІДЛОГ», «02_обмірний план»). This is how the system learns what it actually
               sent, when our own label was wrong.
            """ + COMMON_RULES;

    private static final String SCHEDULE_PROMPT = """
            You read a sheet of Ukrainian design-project documentation that is PROBABLY a room
            schedule (експлікація приміщень) — a table of room number, name and area in m².

            Start with that table. If the same sheet also carries a plan drawing, take its geometry
            too; if it turns out to be a different sheet entirely, extract whatever it does have.
            """ + SHEET_CORE;

    private static final String PLAN_PROMPT = """
            You read a sheet of Ukrainian design-project documentation that is PROBABLY a MEASURE
            PLAN (обмірний план): dimension chains in mm along the room contours, numbered room
            circles, window and door openings, ceiling heights, and OFTEN — but far from always —
            a rooms table on the same sheet.

            ⚠️ TWO SETS OF PLANS: a project often carries the existing layout («як є», «до
            перепланування») AND the new one («після перепланування»), differing ONLY by the
            sheet's title. The base geometry is the one AFTER remodelling — that is what will be
            finished. State which one you read in the first room's note.
            """ + SHEET_CORE;

    private static final String COVERINGS_PROMPT = """
            You read a sheet of Ukrainian design-project documentation that is PROBABLY a COVERINGS
            SPECIFICATION (специфікація покриття підлог і стін / покриттів) — finish materials with
            quantities.

            Start with those lines. Such a sheet frequently also prints per-room areas next to the
            finishes — take those as rooms as well, rather than dropping them.
            """ + SHEET_CORE;

    private static final String GENERIC_PROMPT = """
            You read a sheet of Ukrainian design-project documentation. We could NOT tell what kind
            it is — its file name and stamp matched nothing we recognise, so there is no hint to
            give you, and you must not assume it is useless: a sheet whose name we cannot read is
            usually a perfectly ordinary plan.

            Read its stamp, report it in sheetTitle, and take everything below that it carries. If
            it genuinely holds no room data (a title page, an index, a 3D view, an elevation),
            return empty lists and say so in sheetTitle — that is a useful answer too.
            """ + SHEET_CORE;

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
                    "openings", "confidence", "note", "uncertain"),
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
                    Map.entry("note", STRING),
                    Map.entry("uncertain", arr(STRING))));

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
            List.of("sheetTitle", "floors", "coverings", "totals", "ceilingHeights", "warnings"),
            Map.of(
                    // What the sheet says it is, in its own words. Our label was a guess made from a
                    // filename before anything was read; this is the sheet answering for itself.
                    "sheetTitle", STRING,
                    "floors", arr(FLOOR),
                    "coverings", arr(COVERING),
                    "totals", TOTALS,
                    "ceilingHeights", arr(CEILING),
                    "warnings", arr(STRING)));
}
