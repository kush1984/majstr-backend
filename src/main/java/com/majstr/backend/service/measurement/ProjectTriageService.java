package com.majstr.backend.service.measurement;

import com.majstr.backend.dto.ProjectTriageRequest;
import com.majstr.backend.dto.ProjectTriageResponse;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.AiExtractionException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.ProjectService;
import com.majstr.backend.service.ai.AiFlow;
import com.majstr.backend.service.ai.AiExtractors;
import com.majstr.backend.service.ai.AiInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One cheap pass that reads a whole documentation set's TITLES and says what each sheet is.
 *
 * <p><b>Why this exists.</b> Until now a sheet's fate was decided by keyword lists in the client:
 * eight Ukrainian patterns, no Russian, no English. They were derived from the projects on hand, so
 * they worked on those and failed on the next studio — and failing meant the sheet was never sent at
 * all, which is invisible from the outside. On one real 19-sheet project not a single page matched
 * and the import did nothing. A vocabulary cannot be finished; there is always one more way to title
 * a drawing.</p>
 *
 * <p>So the classification is done by the model, from the sheet's own title block, and the keyword
 * lists survive only as a fallback for when this call cannot run (no key, offline, a sheet with no
 * text layer at all). This is the mechanism the album flow already uses — its stage-1
 * {@code INVENTORY_PROMPT} does the same job — brought over to project import.</p>
 *
 * <p>It reads TEXT, not pages. A title lives in the text layer, so a forty-four-sheet archive can be
 * understood for a fraction of one page-image call, and nothing expensive is spent on sheets that
 * turn out to be a title page or a furniture plan.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectTriageService {

    /** Kinds the client already understands — returning anything else would be untranslatable. */
    private static final List<String> KINDS =
            List.of("PLAN_MEASURE", "ROOM_SCHEDULE", "COVERINGS", "ELECTRICAL", "OTHER");
    private static final List<String> VERSIONS = List.of("AFTER", "EXISTING", "UNKNOWN");
    /** Enough of a sheet to hold its title block; a plan's text is mostly digits anyway. */
    private static final int PER_SHEET_CHARS = 2500;

    private final FeatureGuard featureGuard;
    private final ProjectService projectService;
    private final AiExtractors extractors;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public ProjectTriageResponse triage(UUID ownerId, UUID objectId, ProjectTriageRequest req) {
        featureGuard.requireFeature(loadUser(ownerId), Feature.PROJECT_IMPORT);
        projectService.loadOwned(objectId, ownerId);

        String json = extractors.forFlow(AiFlow.TRIAGE)
                .requestJson(AiInput.text(sheetsAsText(req)), SYSTEM_PROMPT, SCHEMA);
        List<ProjectTriageResponse.Sheet> sheets = read(json, req);
        log.info("Triage of {} sheets → {} worth reading", req.sheets().size(),
                sheets.stream().filter(ProjectTriageResponse.Sheet::worthReading).count());
        return new ProjectTriageResponse(sheets);
    }

    /**
     * The sheets as one document, each fenced by its id.
     *
     * <p>Head AND tail of each sheet's text, because a title block sits at the END of the extraction
     * order as often as at the start — truncating from the front alone threw away the very thing this
     * call is asking about.</p>
     */
    private String sheetsAsText(ProjectTriageRequest req) {
        StringBuilder sb = new StringBuilder();
        for (ProjectTriageRequest.Sheet sheet : req.sheets()) {
            String text = sheet.text() == null ? "" : sheet.text().replaceAll("\\s+", " ").trim();
            if (text.length() > PER_SHEET_CHARS) {
                int half = PER_SHEET_CHARS / 2;
                text = text.substring(0, half) + " […] " + text.substring(text.length() - half);
            }
            sb.append("\n=== SHEET id=").append(sheet.id())
                    .append(" file=\"").append(sheet.name() == null ? "" : sheet.name()).append("\"\n")
                    .append(text.isEmpty() ? "(no text layer)" : text).append('\n');
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<ProjectTriageResponse.Sheet> read(String json, ProjectTriageRequest req) {
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.error("Triage JSON unreadable: {}", e.getMessage());
            throw new AiExtractionException("error.ai.unavailable", e);
        }
        List<String> known = req.sheets().stream().map(ProjectTriageRequest.Sheet::id).toList();
        List<ProjectTriageResponse.Sheet> out = new ArrayList<>();
        Object list = root.get("sheets");
        if (list instanceof List<?> items) {
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> m)) continue;
                String id = str(m.get("id"));
                // An id we never sent cannot be matched to a file, and acting on it would tick a row
                // that does not exist. Dropped, and said so — a hallucinated sheet is not data.
                if (id == null || !known.contains(id)) {
                    log.warn("Triage returned an unknown sheet id '{}' — ignored", id);
                    continue;
                }
                out.add(new ProjectTriageResponse.Sheet(
                        id,
                        str(m.get("title")),
                        oneOf(str(m.get("kind")), KINDS, "OTHER"),
                        str(m.get("floor")),
                        oneOf(str(m.get("version")), VERSIONS, "UNKNOWN"),
                        bool(m.get("hasRoomTable")),
                        bool(m.get("hasDimensions")),
                        bool(m.get("hasOpeningSizes")),
                        bool(m.get("worthReading")),
                        str(m.get("note"))));
            }
        }
        return withAnythingTheModelForgot(out, req);
    }

    /**
     * A sheet we sent and did not get back is put in the answer anyway, as worth reading.
     *
     * <p>The prompt asks for exactly one entry per sheet, and that is a request, not a guarantee —
     * a model dropping a row from a 44-item list is an ordinary thing for it to do. Without this,
     * such a sheet reaches the client with no verdict at all, and a sheet with no verdict is a
     * sheet that never gets read: <b>precisely the failure this whole triage pass exists to
     * remove</b>, reintroduced one layer higher up.</p>
     *
     * <p>It defaults to {@code worthReading = true} for the same reason the prompt does: a sheet
     * wrongly read costs one call and is shown to the master anyway, while a sheet wrongly skipped
     * is invisible. The note says the sheet was not classified, so the master can see why it is
     * ticked without a title.</p>
     */
    private List<ProjectTriageResponse.Sheet> withAnythingTheModelForgot(
            List<ProjectTriageResponse.Sheet> answered, ProjectTriageRequest req) {
        Set<String> got = answered.stream().map(ProjectTriageResponse.Sheet::id).collect(Collectors.toSet());
        List<ProjectTriageResponse.Sheet> out = new ArrayList<>(answered);
        for (ProjectTriageRequest.Sheet sheet : req.sheets()) {
            if (got.contains(sheet.id())) {
                continue;
            }
            log.warn("Triage did not answer for sheet '{}' ({}) — kept as worth reading",
                    sheet.id(), sheet.name());
            out.add(new ProjectTriageResponse.Sheet(
                    sheet.id(), null, "OTHER", null, "UNKNOWN",
                    false, false, false, true,
                    "Аркуш не вдалося розпізнати — перевірте, чи він потрібен"));
        }
        return out;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean bool(Object o) {
        return o instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(o));
    }

    /** A value outside the agreed set becomes the safe default rather than travelling onwards. */
    private static String oneOf(String value, List<String> allowed, String fallback) {
        if (value == null) return fallback;
        String upper = value.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(upper) ? upper : fallback;
    }

    private User loadUser(UUID ownerId) {
        return userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    // ---- prompt + schema --------------------------------------------------------

    static final String SYSTEM_PROMPT = """
            You are sorting the sheets of ONE construction/interior project so that only the useful
            ones are read in detail afterwards. For every sheet you are given its extracted text.

            WHAT YOU ARE READING. The text is a raw dump in extraction order, not reading order: a
            title block may appear at the end, and a plan's text is mostly loose numbers with no
            structure. That is normal and is not a reason to call a sheet unreadable.

            FOR EACH SHEET, RETURN:
              - id: exactly the id you were given. Never invent one, never merge two sheets.
              - title: the sheet's OWN title as printed («ОБМІРНИЙ ПЛАН», «Экспликация помещений»,
                «02_обмірний план», «FLOOR PLAN»). The file name is a hint and is often wrong —
                prefer the title in the text. Empty string if the sheet really has no title.
              - kind, exactly one of:
                  PLAN_MEASURE  — a measuring/dimension plan: dimension chains, room outlines,
                                  numbered rooms. Titles like обмірний план / обмерный план /
                                  measure plan / план обмірів.
                  ROOM_SCHEDULE — a table of rooms with areas: експлікація / экспликация /
                                  специфікація приміщень / room schedule.
                  COVERINGS     — a finishes specification with quantities.
                  ELECTRICAL    — sockets, switches, lighting, heating layout.
                  OTHER         — anything else: title page, drawings index, furniture plan,
                                  demolition plan, elevations, 3D views, plumbing.
                A sheet may be two things at once (a plan WITH a rooms table). Pick the one it is
                primarily, and record the rest in the flags.
              - floor: which floor THIS SHEET is of, from its title block — "1", "2", "цоколь",
                "мансарда", "підвал". Empty string when the sheet does not say.
                ⚠️ NEVER take it from a room's name: a floor-1 schedule may list a room called
                «Коридор 2 поверху», and that says nothing about the sheet.
              - version: AFTER if this is the layout after remodelling («після перепланування»,
                «после перепланировки», «проектне рішення», «планувальне рішення», «проектований
                план», «proposed»); EXISTING if it is the current one («до перепланування»,
                «існуючий стан», «as-is», and the survey itself — «обмірний план», «обмірювальний
                план», «план обміру», «обмерный план»); UNKNOWN if the sheet does not say. A set
                routinely carries BOTH versions of the same plan with almost identical titles —
                telling them apart is one of the two reasons this pass exists.
                ⚠️ MOST SETS NAME THE WORK, NOT THE STATE, and that is not the same distinction:
                «План/Схема демонтажу» shows what is being removed and «План/Схема монтажу» the new
                partitions only. Neither is a layout version — leave those UNKNOWN and say in the
                note what the sheet is, rather than filing a demolition plan as EXISTING.
              - hasRoomTable / hasDimensions / hasOpeningSizes: what data is actually on the sheet —
                a rooms table with areas; dimension chains; a doors/windows specification or
                per-opening sizes.
              - worthReading: would reading this sheet in detail produce room measurements? True for
                measure plans and rooms tables, for sheets carrying per-room areas or opening sizes,
                and for anything you are unsure about that looks like a plan. False for title pages,
                indexes, 3D views, elevations, furniture and demolition plans.
                When in doubt say TRUE: a sheet wrongly skipped is invisible, while a sheet wrongly
                read costs one call and is shown to the master anyway.
              - note: one short Ukrainian sentence when there is something the master should know —
                a duplicate version, an unreadable sheet, a language you did not expect. Otherwise
                an empty string.

            RULES:
              - Language varies and one set often mixes Ukrainian, Russian and English. Match on
                MEANING, never on the spelling of one language.
              - THE SHEET CODE OFTEN NAMES THE TRADE, and it does so the same way in every studio,
                which makes it worth more than any wording. A code reads
                «contract-building-МАРКА» («2345-12-АР») or just the mark with a sheet number
                («АР-03», «АІ-12», «ОВ-1»), and the mark is the discipline:
                  worth reading — АР архітектурні рішення, АІ інтер'єри, АБ архітектурно-будівельні;
                  another trade — ОВ опалення/вентиляція, ВК водопровід і каналізація, ЕМ/ЕО/ЕТР/ЕЗ
                  електрика (kind ELECTRICAL), КБ/КМ/КД/КМД конструкції, ГП/ГТ генплан, ТХ/ТК
                  технологія, СЗ/РТ зв'язок, ПС/ОС/ПГ сигналізація і пожежогасіння, АД/КЗ дороги,
                  ПОБ організація будівництва, К кошторис.
                ⚠️ «ЕП» is ambiguous: as a STAGE it means ескізний проект, as a MARK електропостачання.
                Stage codes — ЕП, ТЕО, ТЕР, П, РП, Р — sit in their own field and are NOT disciplines.
                ⚠️ The mark is a HINT, not a verdict. Many studios print no code at all, and a small
                one may mark every sheet АР. A sheet with an «other trade» mark that still shows a
                rooms table or dimension chains is worthReading TRUE — the data outranks the code.
              - Return EXACTLY one entry per sheet you were given, in the same order, with the same
                ids. Do not drop a sheet because it looks useless — say so with worthReading false.
              - Judge only from the text in front of you. Do not use anything you may know about a
                project with a similar name; every set is a set you have never seen.
            """;

    private static final Map<String, Object> STRING = Map.of("type", "string");
    private static final Map<String, Object> BOOL = Map.of("type", "boolean");

    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", List.of("sheets"),
            "properties", Map.of("sheets", Map.of(
                    "type", "array",
                    "items", sheetSchema())));

    private static Map<String, Object> sheetSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", STRING);
        properties.put("title", STRING);
        properties.put("kind", STRING);
        properties.put("floor", STRING);
        properties.put("version", STRING);
        properties.put("hasRoomTable", BOOL);
        properties.put("hasDimensions", BOOL);
        properties.put("hasOpeningSizes", BOOL);
        properties.put("worthReading", BOOL);
        properties.put("note", STRING);
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.copyOf(properties.keySet()),
                "properties", properties);
    }
}
