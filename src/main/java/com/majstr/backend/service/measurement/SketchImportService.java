package com.majstr.backend.service.measurement;

import com.majstr.backend.dto.MeasurementsResponse;
import com.majstr.backend.dto.SketchCommitRequest;
import com.majstr.backend.dto.SketchParseResponse;
import com.majstr.backend.entity.MeasurementType;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.AiExtractionException;
import com.majstr.backend.exception.CatalogImportException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.ProjectService;
import com.majstr.backend.service.ai.AiInput;
import com.majstr.backend.service.ai.JsonExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Recognise a hand-drawn room sketch photo into a DRAFT set of measurement rooms/elements
 * via Claude vision — PRO-gated ({@code Feature.SKETCH_IMPORT}). The third prompt/schema on
 * top of {@link ClaudeEstimateExtractor} (estimate + receipt were the first two); it reuses
 * that ONE Anthropic client via {@link ClaudeEstimateExtractor#requestJson}.
 *
 * <p>{@code parse} normalises the model's output into the same payload shape the manual editor
 * uses (so the review screen redraws the identical schema for the master to compare against the
 * photo) and computes each {@code result} with {@link MeasurementCalc} — the model never
 * calculates area. Unreadable sizes are left blank + flagged (low confidence), never guessed.
 * Nothing is written; the image is discarded. {@code commit} creates the master-confirmed set
 * via {@link MeasurementService#createFromSketch} (result recomputed server-side).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SketchImportService {

    /** The shapes the schema module knows — the LLM must return one of these or the plane is dropped. */
    private static final Set<String> SHAPES = Set.of("rect", "trap", "attic", "tri", "cut");

    private final FeatureGuard featureGuard;
    private final UserRepository userRepository;
    private final ProjectService projectService;
    /** Whichever provider `app.ai.provider` selected — this flow does not care which. */
    private final JsonExtractor extractor;
    private final MeasurementService measurementService;
    private final MeasurementCalc calc;
    private final ObjectMapper objectMapper;

    // ---- parse ----------------------------------------------------------------

    public SketchParseResponse parse(UUID ownerId, UUID objectId,
                                     String filename, String contentType, byte[] bytes) {
        featureGuard.requireFeature(loadUser(ownerId), Feature.SKETCH_IMPORT);
        projectService.loadOwned(objectId, ownerId); // existence + ownership (404 / 403)

        String mediaType = imageMediaType(filename, contentType);
        if (mediaType == null) {
            throw new CatalogImportException("error.import.unsupported");
        }
        var content = AiInput.image(mediaType, bytes,
                "Recognise this hand-drawn room sketch into rooms, surfaces and their sizes.");
        String json = extractor.requestJson(content, SKETCH_PROMPT, SCHEMA);
        return toReview(json);
    }

    // ---- commit ---------------------------------------------------------------

    public MeasurementsResponse commit(UUID ownerId, UUID objectId, SketchCommitRequest req) {
        featureGuard.requireFeature(loadUser(ownerId), Feature.SKETCH_IMPORT);
        MeasurementsResponse tree = measurementService.createFromSketch(objectId, ownerId, req.rooms());
        int items = req.rooms().stream().mapToInt(r -> r.items().size()).sum();
        log.info("Sketch import for {} → object {} (+{} rooms, +{} elements)",
                ownerId, objectId, req.rooms().size(), items);
        return tree;
    }

    // ---- LLM JSON → review DTO -------------------------------------------------

    @SuppressWarnings("unchecked")
    private SketchParseResponse toReview(String json) {
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse sketch extraction JSON: {}", e.getMessage());
            throw new AiExtractionException("error.ai.unavailable", e);
        }

        String unitGuess = normUnit(str(root.get("unitGuess")));
        double factor = factor(unitGuess);

        List<SketchParseResponse.Room> rooms = new ArrayList<>();
        int roomNo = 0;
        for (Object ro : asList(root.get("rooms"))) {
            if (!(ro instanceof Map<?, ?> rm)) continue;
            roomNo++;
            String roomName = str(rm.get("name"));
            if (roomName == null) roomName = "Кімната " + roomNo;

            List<SketchParseResponse.Item> items = new ArrayList<>();
            for (Object io : asList(rm.get("items"))) {
                if (io instanceof Map<?, ?> im) {
                    SketchParseResponse.Item item = mapItem((Map<String, Object>) im, unitGuess, factor);
                    if (item != null) items.add(item);
                }
            }
            rooms.add(new SketchParseResponse.Room(roomName, conf(rm.get("confidence")), items));
        }

        List<String> warnings = new ArrayList<>();
        for (Object w : asList(root.get("warnings"))) {
            String s = str(w);
            if (s != null) warnings.add(s);
        }
        return new SketchParseResponse(rooms, unitGuess, warnings);
    }

    @SuppressWarnings("unchecked")
    private SketchParseResponse.Item mapItem(Map<String, Object> im, String unitGuess, double factor) {
        MeasurementType type = parseType(str(im.get("type")));
        Unit unit = type.unit();
        String name = str(im.get("name"));

        Map<String, Object> payload;
        switch (type) {
            case SURFACE -> {
                List<Map<String, Object>> segments = new ArrayList<>();
                for (Object po : asList(im.get("planes"))) {
                    if (!(po instanceof Map<?, ?> pm)) continue;
                    String shape = lower(str(pm.get("shape")));
                    if (shape == null || !SHAPES.contains(shape)) continue; // unknown shape → skip
                    Map<String, Object> values = new LinkedHashMap<>();
                    if (pm.get("values") instanceof Map<?, ?> vm) {
                        for (String key : List.of("a", "b", "c", "d", "h")) {
                            Double v = dbl(vm.get(key));
                            if (v != null && v > 0) values.put(key, v); // omit unreadable → blank field
                        }
                    }
                    Map<String, Object> seg = new LinkedHashMap<>();
                    seg.put("shape", shape);
                    String mode = str(pm.get("mode"));
                    if (mode != null) seg.put("mode", mode);
                    seg.put("values", values);
                    segments.add(seg);
                }
                List<Map<String, Object>> openings = new ArrayList<>();
                for (Object oo : asList(im.get("openings"))) {
                    if (!(oo instanceof Map<?, ?> om)) continue;
                    Double w = dbl(om.get("w"));
                    Double h = dbl(om.get("h"));
                    if (w != null && w > 0 && h != null && h > 0) {
                        openings.add(Map.of("w", w, "h", h, "n", 1));
                    }
                }
                payload = new LinkedHashMap<>();
                payload.put("unit", unitGuess); // surfaces carry the unit; openings share it
                payload.put("segments", segments);
                payload.put("openings", openings);
            }
            case PARTITION -> {
                Map<String, Object> p = asMap(im.get("partition"));
                payload = new LinkedHashMap<>();
                payload.put("height", metres(p.get("H"), factor));
                payload.put("width", metres(p.get("W"), factor));
                payload.put("depth", metres(p.get("D"), factor));
                payload.put("faces", faces(p.get("faces"), List.of("left", "right", "end", "top")));
            }
            case LINEAR -> {
                Map<String, Object> l = asMap(im.get("linear"));
                Integer qty = intOf(l.get("qty"));
                payload = new LinkedHashMap<>();
                payload.put("height", metres(l.get("H"), factor));
                payload.put("width", metres(l.get("W"), factor));
                payload.put("sides", faces(l.get("sides"), List.of("left", "right", "top", "bottom")));
                payload.put("qty", qty == null || qty < 1 ? 1 : qty);
            }
            default -> {
                return null;
            }
        }

        JsonNode node = objectMapper.valueToTree(payload);
        BigDecimal result = null;
        try {
            result = calc.compute(type, node);
        } catch (RuntimeException ex) {
            // Incomplete/invalid (an unreadable size) — leave the field blank, flag for a check.
        }
        // A ZERO result is not a measurement of nothing — it means nothing in this element was
        // readable (every plane dropped as an unknown shape, or a dimension left at 0). Only
        // SURFACE used to be caught, because Shapes throws on a=0; a dropped-plane surface and
        // an unreadable PARTITION/LINEAR both computed a clean 0.000 and kept the model's
        // original "high" confidence — a silent, confident zero straight into the review.
        if (result != null && result.signum() == 0) {
            result = null;
        }
        String confidence = result == null ? "low" : conf(im.get("confidence"));
        return new SketchParseResponse.Item(type, name, unit, confidence, str(im.get("note")), node, result);
    }

    // ---- helpers --------------------------------------------------------------

    private User loadUser(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    /** Convert a raw dimension in the sheet's unit to metres (PARTITION/LINEAR have no unit field). */
    private static double metres(Object raw, double factor) {
        Double v = dbl(raw);
        return v == null || v <= 0 ? 0 : v * factor;
    }

    private static Map<String, Object> faces(Object raw, List<String> keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> in = asMap(raw);
        for (String k : keys) {
            Object v = in.get(k);
            out.put(k, v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v)));
        }
        return out;
    }

    private static MeasurementType parseType(String raw) {
        if (raw == null) return MeasurementType.SURFACE;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "PARTITION" -> MeasurementType.PARTITION;
            case "LINEAR" -> MeasurementType.LINEAR;
            default -> MeasurementType.SURFACE;
        };
    }

    private static String normUnit(String raw) {
        if (raw == null) return "M";
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "мм", "mm" -> "MM";
            case "см", "cm" -> "CM";
            default -> "M";
        };
    }

    private static double factor(String unit) {
        return switch (unit) {
            case "MM" -> 0.001;
            case "CM" -> 0.01;
            default -> 1;
        };
    }

    private static String conf(Object raw) {
        String s = lower(str(raw));
        return (s != null && (s.equals("high") || s.equals("medium"))) ? s : "low";
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object v) {
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static Double dbl(Object v) {
        if (v == null) return null;
        try {
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? null : Double.parseDouble(s.replace(',', '.'));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer intOf(Object v) {
        Double d = dbl(v);
        return d == null ? null : (int) Math.round(d);
    }

    /** The image media type to send to Claude, or null if this isn't a supported image upload. */
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

    // ---- prompt + schema ------------------------------------------------------

    private static final String SKETCH_PROMPT = """
            You read a Ukrainian building contractor's HAND-DRAWN room sketch (кроки) photographed
            with a phone: a plan or wall elevation with sizes written by hand, possibly several rooms
            on one sheet. Abbreviations are common (ст.=стіна/wall, стл.=стеля/ceiling, підл.=підлога/
            floor, пер.=перегородка/partition, відк.=відкоси/reveals). Dimension arrows link a number
            to a side.

            YOUR TASK: recognise the rooms, the surfaces in each, and THEIR SIZES; attach each written
            size to the correct side of the correct figure; pick the figure from the allowed shapes.
            You are extracting geometry only — the system computes areas.

            For each room return name (or "" → the system names it) and confidence.
            For each element return:
              - type: "SURFACE" (a wall/ceiling/floor area, m²), "PARTITION" (a free-standing partition
                measured by faces), or "LINEAR" (reveals/skirting run, linear metres). Most are SURFACE.
              - name: "Стеля", "Стіни", "Підлога", "Відкоси", … (Ukrainian; "" if unnamed).
              - unit: "M2" for SURFACE/PARTITION, "LINEAR_METER" for LINEAR.
              - confidence: high/medium/low. Anything you inferred or half-read → low.
              - note: a short Ukrainian note when you guessed or a size was unreadable (else "").
              - planes (SURFACE only): the figures the surface is made of. Each plane:
                  shape: one of "rect" (rectangle a×b), "trap" (trapezoid a=top,b=bottom,h),
                         "attic" (mansard a=width,b=side wall,h=apex), "tri" (triangle b=base,h=height),
                         "cut" (cut corner a,b,c,d). Use the SIMPLEST shape that fits.
                  mode: "" normally; for "attic" use "sym" or "asym"; for "tri" use "bh" or "sss".
                  values: {a,b,c,d,h} — fill ONLY the letters that shape uses; put 0 for any size you
                          cannot read. A rectangular room ceiling = one "rect" plane {a,b}.
              - openings (SURFACE only): windows/doors to subtract, each {w,h}. None → [].
              - partition (PARTITION only): {H,W,D, faces:{left,right,end,top}} — sizes as written.
              - linear (LINEAR only): {H,W, sides:{left,right,top,bottom}, qty} — sizes as written.
                For a non-matching type, still return the object with zeros / all-false / qty 1.

            unitGuess: the unit the WRITTEN numbers are in — "мм", "см" or "м". If not stated, infer from
            plausibility (3,2 → м; 320 → см; 3200 → мм) and add a warning.
            warnings: sheet-level notes ("масштаб не вказано", "частина нерозбірлива").

            HARD RULES:
              - Do NOT invent a size that is not on the sketch. Unreadable number → 0 (never a guess),
                and set that element's confidence to "low" with a note.
              - Do NOT compute areas. Only transcribe shapes + sizes.
              - If unsure of the figure, choose the simplest compatible one ("rect") and set low confidence.
              - Return ONLY JSON matching the schema, no prose.
            """;

    private static final Map<String, Object> STRING = Map.of("type", "string");
    private static final Map<String, Object> NUMBER = Map.of("type", "number");
    private static final Map<String, Object> BOOLEAN = Map.of("type", "boolean");

    private static Map<String, Object> obj(List<String> required, Map<String, Object> properties) {
        return Map.of("type", "object", "additionalProperties", false,
                "required", required, "properties", properties);
    }

    private static Map<String, Object> arr(Object items) {
        return Map.of("type", "array", "items", items);
    }

    private static final Map<String, Object> VALUES = obj(
            List.of("a", "b", "c", "d", "h"),
            Map.of("a", NUMBER, "b", NUMBER, "c", NUMBER, "d", NUMBER, "h", NUMBER));

    private static final Map<String, Object> PLANE = obj(
            List.of("shape", "mode", "values"),
            Map.of("shape", STRING, "mode", STRING, "values", VALUES));

    private static final Map<String, Object> OPENING = obj(
            List.of("w", "h"), Map.of("w", NUMBER, "h", NUMBER));

    private static final Map<String, Object> FACES = obj(
            List.of("left", "right", "end", "top"),
            Map.of("left", BOOLEAN, "right", BOOLEAN, "end", BOOLEAN, "top", BOOLEAN));

    private static final Map<String, Object> SIDES = obj(
            List.of("left", "right", "top", "bottom"),
            Map.of("left", BOOLEAN, "right", BOOLEAN, "top", BOOLEAN, "bottom", BOOLEAN));

    private static final Map<String, Object> PARTITION = obj(
            List.of("H", "W", "D", "faces"),
            Map.of("H", NUMBER, "W", NUMBER, "D", NUMBER, "faces", FACES));

    private static final Map<String, Object> LINEAR = obj(
            List.of("H", "W", "sides", "qty"),
            Map.of("H", NUMBER, "W", NUMBER, "sides", SIDES, "qty", NUMBER));

    private static Map<String, Object> itemSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("type", STRING);
        props.put("name", STRING);
        props.put("unit", STRING);
        props.put("confidence", STRING);
        props.put("note", STRING);
        props.put("planes", arr(PLANE));
        props.put("openings", arr(OPENING));
        props.put("partition", PARTITION);
        props.put("linear", LINEAR);
        return obj(List.of("type", "name", "unit", "confidence", "note",
                "planes", "openings", "partition", "linear"), props);
    }

    private static Map<String, Object> roomSchema() {
        return obj(List.of("name", "confidence", "items"),
                Map.of("name", STRING, "confidence", STRING, "items", arr(itemSchema())));
    }

    private static final Map<String, Object> SCHEMA = obj(
            List.of("rooms", "unitGuess", "warnings"),
            Map.of("rooms", arr(roomSchema()), "unitGuess", STRING, "warnings", arr(STRING)));
}
