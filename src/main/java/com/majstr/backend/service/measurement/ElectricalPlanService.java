package com.majstr.backend.service.measurement;

import com.majstr.backend.dto.ElectricalPlanParseResponse;
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
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Read the ELECTRICAL POINTS off a plan (PDF or photo) with LLM vision — one prompt on the
 * {@link com.majstr.backend.service.ai.JsonExtractor} seam, whichever provider it resolves to.
 *
 * <p><b>The split that makes this safe:</b> the model counts DISCRETE symbols against the
 * drawing's legend — a reliable reading task, like a receipt. It is explicitly forbidden to
 * measure anything: no chase lengths, no LED-strip metres, no scale inference. Lengths are
 * geometry in scale, where a plausible-but-wrong number would flow straight into money;
 * those are computed deterministically ({@link MeasurementCalc#compute}) or entered by hand.
 * LED strip is only FLAGGED as present ({@code ledStripPresent}), never measured.</p>
 *
 * <p>Parse writes nothing and the file is discarded. The master reviews the counts, then
 * commits them through the ordinary "add measurement item" path as an
 * {@code ELECTRICAL_POINTS} element (unit шт), so substitution into estimate lines keeps
 * working purely by unit.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElectricalPlanService {

    private final FeatureGuard featureGuard;
    private final UserRepository userRepository;
    private final ProjectService projectService;
    /** Whichever provider `app.ai.provider` selected — this flow does not care which. */
    private final JsonExtractor extractor;
    private final ObjectMapper objectMapper;

    public ElectricalPlanParseResponse parse(UUID ownerId, UUID objectId,
                                             String filename, String contentType, byte[] bytes) {
        featureGuard.requireFeature(loadUser(ownerId), Feature.MEASUREMENTS);
        projectService.loadOwned(objectId, ownerId); // existence + ownership (404 / 403)

        String instruction = "Порахуй електричні точки на цьому плані за його легендою.";
        List<AiInput> content;
        if (isPdf(filename, contentType)) {
            content = AiInput.pdf(bytes, instruction);
        } else {
            String mediaType = imageMediaType(filename, contentType);
            if (mediaType == null) {
                throw new CatalogImportException("error.import.unsupported");
            }
            content = AiInput.image(mediaType, bytes, instruction);
        }
        return toReview(extractor.requestJson(content, PLAN_PROMPT, SCHEMA));
    }

    // ---- LLM JSON → review DTO -------------------------------------------------

    @SuppressWarnings("unchecked")
    private ElectricalPlanParseResponse toReview(String json) {
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse electrical-plan JSON: {}", e.getMessage());
            throw new AiExtractionException("error.ai.unavailable", e);
        }

        List<ElectricalPlanParseResponse.Point> points = new ArrayList<>();
        for (Object o : asList(root.get("points"))) {
            ElectricalPlanParseResponse.Point p = toPoint(o);
            if (p != null) points.add(p);
        }

        List<String> warnings = new ArrayList<>();
        for (Object w : asList(root.get("warnings"))) {
            String s = str(w);
            if (s != null) warnings.add(s);
        }
        boolean led = Boolean.TRUE.equals(root.get("ledStripPresent"))
                || "true".equalsIgnoreCase(String.valueOf(root.get("ledStripPresent")));
        return new ElectricalPlanParseResponse(points, led, warnings);
    }

    private static ElectricalPlanParseResponse.Point toPoint(Object o) {
        if (!(o instanceof Map<?, ?> m)) return null;
        String type = str(m.get("type"));
        if (type == null) return null; // a row without a type is noise
        Integer count = intOf(m.get("count"));
        List<BigDecimal> heights = new ArrayList<>();
        for (Object h : asList(m.get("heights"))) {
            BigDecimal v = bd(h);
            if (v != null && v.signum() > 0) heights.add(v);
        }
        String confidence = conf(m.get("confidence"));
        // A missing/zero count can't be trusted — flag it instead of inventing one.
        if (count == null || count <= 0) {
            count = 0;
            confidence = "low";
        }
        return new ElectricalPlanParseResponse.Point(type, count, heights, confidence, str(m.get("note")));
    }

    // ---- helpers --------------------------------------------------------------

    private User loadUser(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    private static boolean isPdf(String filename, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("pdf")) return true;
        String n = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        return n.endsWith(".pdf");
    }

    private static String imageMediaType(String filename, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.equals("image/jpeg") || ct.equals("image/jpg")) return "image/jpeg";
        if (ct.equals("image/png")) return "image/png";
        if (ct.equals("image/webp")) return "image/webp";
        String n = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        return null;
    }

    private static String conf(Object raw) {
        String s = raw == null ? null : String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return ("high".equals(s) || "medium".equals(s)) ? s : "low";
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object v) {
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer intOf(Object v) {
        BigDecimal d = bd(v);
        return d == null ? null : d.intValue();
    }

    private static BigDecimal bd(Object v) {
        if (v == null) return null;
        try {
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? null : new BigDecimal(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- prompt + schema ------------------------------------------------------
    // The legend wording below is taken verbatim from real Ukrainian design-project plans
    // ("Умовні позначення" block), so the model matches the labels it will actually see.

    private static final String PLAN_PROMPT = """
            You read a Ukrainian interior design project's ELECTRICAL PLAN (PDF or photo):
            either «План розташування вимикачив і розеток» (sockets/switches) or
            «План розташування елементів освітлення» (lighting). A legend block titled
            «Умовні позначення» maps each symbol to its meaning — READ THE LEGEND FIRST and
            match every symbol on the drawing against it.

            Typical legend entries (wording varies slightly between projects):
              sockets/switches plan — «Вимикач 1 клавішний», «Вимикач 2 клавішний»,
                «Вимикач прохідний 1 кл.», «Вимикач прохідний 2 кл.», «Вимикач витяжки»,
                «Розетка електрична» (often marked Е), «Розетка ТВ» (TV), «Розетка інтернет»,
                «Роутер» (Wi-Fi), «Трансформатор LED», «Вивід живлення» (often marked В);
              lighting plan — «Вбудований світильник», «Вбудований світильник на 2 лампи»,
                «Світильник підвісний», «Бра», «Вивід живлення», «ЛЕД підсвітка»,
                «Точка під'єднання ЛЕД».

            YOUR TASK: return a FLAT list of point types. For each legend type present on the
            drawing, COUNT the discrete symbols of that type and READ the height annotations
            next to them (they look like «h = 900», «h = 2600» — millimetres above the finished
            floor). Do NOT group by room and do NOT read room sizes — the master distributes the
            points across rooms himself in the calculator.

            For each point TYPE return:
              - type: the legend's own Ukrainian wording (keep it as printed).
              - count: how many symbols of that type on the whole drawing (the selected page[s]).
              - heights: every distinct h= value seen for this type, mm. [] if none.
              - confidence: high/medium/low. Anything inferred or half-read → low.
              - note: a short Ukrainian note when unsure (else "").

            HARD RULES — these exist because a wrong number here becomes wrong money:
              - Reading a PRINTED number (a height «h=…») is allowed and expected. MEASURING is
                NOT: never infer a length from pixels/scale, never read a room size, never output a
                chase (штроба) metre or cable run. Count discrete symbols; read printed heights.
              - «ЛЕД підсвітка» is drawn as LINES, not a symbol. NEVER estimate its length.
                Only set ledStripPresent: true if any LED strip appears on the plan.
                (A «Точка під'єднання ЛЕД» / «Вивід живлення підсвітки» IS a discrete point —
                count those normally.)
              - Do NOT invent a type that is not in the legend. Unsure which legend entry a
                symbol matches → pick the closest one, set confidence "low" and say why in note.
              - If you cannot count a type reliably, return count 0 with confidence "low" and a
                note — never a guess.
              - Return ONLY JSON matching the schema, no prose.

            warnings: sheet-level notes («частина плану нерозбірлива», «легенда обрізана»).
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

    private static Map<String, Object> pointSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("type", STRING);
        props.put("count", NUMBER);
        props.put("heights", arr(NUMBER));
        props.put("confidence", STRING);
        props.put("note", STRING);
        return obj(List.of("type", "count", "heights", "confidence", "note"), props);
    }

    private static final Map<String, Object> SCHEMA = obj(
            List.of("points", "ledStripPresent", "warnings"),
            Map.of("points", arr(pointSchema()),
                    "ledStripPresent", BOOLEAN,
                    "warnings", arr(STRING)));
}
