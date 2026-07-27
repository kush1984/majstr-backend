package com.majstr.backend.service.album;

import com.majstr.backend.config.AnthropicProperties;
import com.majstr.backend.exception.AiExtractionException;
import com.majstr.backend.service.album.AlbumExtraction.HeatingResult;
import com.majstr.backend.service.album.AlbumExtraction.Inventory;
import com.majstr.backend.service.album.AlbumExtraction.LightingResult;
import com.majstr.backend.service.album.AlbumExtraction.PointsResult;
import com.majstr.backend.service.album.AlbumExtraction.RoomsAndOpenings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Design-album recognition via the Anthropic Messages API (Opus tier) — the multi-pass
 * extractor behind the "імпорт дизайн-альбому → електричний takeoff" feature. Raw HTTP
 * ({@link RestClient}) matching the codebase precedent ({@code ClaudeEstimateExtractor}),
 * but with the lessons applied from day one:
 * <ul>
 *   <li><b>Timeouts</b>: connect 5 s, read 5 min — a hung upstream must not pin a thread
 *       forever (the album passes are minutes-long; callers run them on an async job,
 *       never on a request thread).</li>
 *   <li><b>Structured outputs</b>: every pass forces {@code output_config.format} with a
 *       per-stage JSON schema, so responses parse straight into {@link AlbumExtraction}
 *       records — no defensive string fishing.</li>
 *   <li><b>Prompt caching</b>: the system prompt is sent as a block array with
 *       {@code cache_control} on the shared part, and the album document leads the user
 *       content — passes 2..N of the same album read the cache at ~10% of the input price.</li>
 *   <li><b>stop_reason checks</b>: {@code max_tokens} and {@code refusal} become explicit
 *       errors instead of silently-truncated JSON.</li>
 * </ul>
 *
 * Opus 4.7+ constraints honoured here: NO {@code temperature}/{@code top_p} and NO
 * {@code budget_tokens} (all three are a 400); thinking is {@code {"type":"adaptive"}}.
 *
 * <p>Prompts follow {@code system-prompt-extraction.md}; the wire contract is
 * {@code extraction-schema.json}. The extractor only extracts — all cable/chase math
 * lives in {@link ElectroTakeoffCalc}, and cross-checks in the orchestrating service.</p>
 */
@Slf4j
@Component
public class ClaudeAlbumExtractor {

    private static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_BASE_MS = 1_000L;

    /** Album passes produce large JSON — never let the configured chat-size cap truncate them. */
    private static final int MIN_MAX_TOKENS = 16_000;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(5);

    // ---- prompts (condensed from system-prompt-extraction.md; keep byte-stable: it is the cache prefix) ----

    static final String COMMON_PROMPT = """
            Ти — інженер-кошторисник, який розбирає дизайн-проєкт/обміри житла для розрахунку
            електромонтажних робіт. Працюєш українською. Відповідаєш СТРОГО у форматі JSON за
            наданою схемою — без жодного тексту поза JSON.

            ЗАЛІЗНІ ПРАВИЛА ЧЕСНОСТІ:
            1. Відсутні в документі дані НЕ вигадуй і не переноси з "типових" проєктів.
               Числове поле, якого немає в документі -> null; факт відсутності -> рядок у missing.
            2. Пораховане по символах/ланцюжках плану маркуй status:"counted"; взяте з таблиць
               специфікацій дизайнера — status:"from_spec"; власні припущення — status:"assumed"
               з поясненням у note.
            3. Неоднозначне читання (злиплі символи, спільний підпис, скупчення блоків) ->
               найкраще прочитання + verify:true + опис місця в uncertain.
            4. Фото креслення під кутом: читай ТІЛЬКИ написи, таблиці і символи; розмірні
               відстані з фото не знімай — розмір без підпису = null.
            5. Заголовок на аркуші авторитетніший за ім'я файлу.

            УМОВНІ ПОЗНАЧЕННЯ: Нпр = висота прорізу; Нпд = висота підвіконня; H=... = висота стелі;
            h=... = висота установки точки від чистової підлоги; ланцюжки в мм ("5 000" = 5000 мм);
            гр.N = група світла; Пр = прохідний вимикач; В = вимикач витяжки. Легенда на аркуші
            авторитетніша за загальні знання.

            САМОКОНТРОЛЬ ГЕОМЕТРІЇ: площа кімнати з ланцюжків має збігатися з експлікацією
            до ±0,3 м²; не збігається після перечитування — запиши ОБИДВА числа як є (не підганяй),
            verify:true, причина в note.
            """;

    static final String INVENTORY_PROMPT = """
            ЗАВДАННЯ: пройди ВСІ сторінки документа і склади:
            1. meta (is_design_album=false, якщо це взагалі не проєкт/обміри — поясни в note аркуша).
            2. sheets: заголовок З АРКУША, тип, поверх, читабельність; версії (до/після
               перепланування) розрізняй заголовком і фіксуй у note.
            3. data_availability: для кожного data_kind — статус з аркушами-джерелами.
               electrical_point_counts = "available" ЛИШЕ якщо є таблиці специфікацій з
               кількостями; самі символи = "manual_count_needed". floor_heating_type = "missing",
               якщо тип (електрична/водяна) ніде не написано.
            """;

    static final String ROOMS_PROMPT = """
            ЗАВДАННЯ: з обмірних планів та експлікації витягни rooms і openings.
            Кімнати: габарити З РОЗМІРНИХ ЛАНЦЮЖКІВ (складні форми — сума прямокутників,
            запиши як текст "A×B + C×D"), периметр за контуром у метрах (прямокутна =
            2×(a+b); Г/Т-подібні — сума сегментів контуру; контур не відновлюється -> null),
            розрахована площа, площа з експлікації, висота стелі
            (шукай: обмірний план -> план стель -> монтажний -> розгортки; ніде немає -> null +
            missing). Мансардні скоси — в ceiling_note.
            Прорізи: тип, ширина з ланцюжка, висота (Нпр/специфікація; ніде немає -> null),
            підвіконня (Нпд), to_floor, марка; двері між двома кімнатами — room_a і room_b.
            Специфікації дверей/вікон авторитетні (from_spec); ланцюжки з плану — counted.
            Якщо є два комплекти обмірів — базова геометрія ПІСЛЯ перепланування.
            """;

    static final String POINTS_PROMPT = """
            ЗАВДАННЯ: порахуй УСІ електроточки вказаного поверху з плану розеток/вимикачів.
            1. Спершу знайди легенду умовних позначень на аркуші.
            2. Йди кімната за кімнатою; кожен блок однотипних точок = один запис
               (тип, кількість, висота h=, призначення з виноски).
            3. Скупчення символів рахуй уважно; перекритий символ — найкраще прочитання + verify.
            4. Якщо на аркуші Є таблиця специфікації з кількостями — перепиши її (from_spec),
               свій підрахунок використай як перевірку; розбіжність -> verify:true.
            5. ПЕРЕХРЕСНА ЗВІРКА: звір кількість і типи вимикачів з планом вимикання/груп
               світла того ж поверху, якщо він є — кожен вимикач десь щось вмикає.
               Розбіжність -> перерахуй; не зійшлося вдруге -> verify:true + uncertain.
            6. Виводи живлення (кондиціонер/витяжка/домофон) — power_outlet_220 або
               doorphone_outlet, призначення в purpose.
            """;

    static final String LIGHTING_PROMPT = """
            ЗАВДАННЯ: з планів освітлення і планів вимикання/груп світла витягни:
            1. lighting: прилади по кімнатах × типах за легендою аркуша, позиційні марки,
               висоти, довжини LED-профілів/треків (не підписана -> null). Кількість точок
               під'єднання LED — оціночна -> verify:true.
            2. light_groups: що вмикає, яким вимикачем, з скількох місць (прохідні — 2/3).
               Груп на планах немає взагалі -> порожній масив + рядок у missing.
            3. Специфікація світильників без кількостей -> зафіксуй у missing.
            """;

    static final String HEATING_PROMPT = """
            ЗАВДАННЯ:
            1. floor_heating: зони з площами по поверхах; system_type СТРОГО з тексту альбому
               ("електрична" -> electric; колектор/труби -> water; не написано -> unknown).
            2. З планів вентиляції/кондиціонування: внутрішні блоки кондиціонерів і витяжні
               вентилятори -> electrical_points (power_outlet_220 з purpose).
            3. panel_location: позначки щита ("ЩО", "ЩР", "електрична шафа") на всіх планах;
               не знайдено -> known:false (це важлива чесна відповідь, не помилка).
            """;

    private final AnthropicProperties props;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public ClaudeAlbumExtractor(AnthropicProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        // Explicit timeouts — RestClient.create() has none, and a stalled upstream would
        // otherwise hold the worker thread forever (audit finding H3).
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    // ---- the five passes -------------------------------------------------------

    /** Stage 1: sheet inventory + data-availability matrix over the whole album. */
    public Inventory inventory(byte[] albumPdf, String pageToFileMap) {
        String task = "Інвентаризуй цей альбом." + (pageToFileMap == null ? ""
                : " Мапа сторінок до вихідних файлів:\n" + pageToFileMap);
        String json = requestJson(albumPdf, INVENTORY_PROMPT, task, AlbumSchemas.INVENTORY);
        return read(json, Inventory.class);
    }

    /** Stage 2A: rooms + openings from the measurement plans / explication sheets. */
    public RoomsAndOpenings extractRooms(byte[] albumPdf, List<Integer> sheetIndexes) {
        String task = "Витягни кімнати і прорізи. Основні аркуші: " + sheetIndexes + ".";
        String json = requestJson(albumPdf, ROOMS_PROMPT, task, AlbumSchemas.ROOMS_AND_OPENINGS);
        return read(json, RoomsAndOpenings.class);
    }

    /**
     * Stage 2B: electrical points of ONE floor (one call per floor keeps counts focused).
     * {@code roomNames} is optional context — the electro-only flow runs without the rooms
     * pass, so the model then takes room names from the plan itself.
     */
    public PointsResult extractPoints(byte[] albumPdf, int floor, List<Integer> sheetIndexes,
                                      List<String> roomNames) {
        String task = "Порахуй електроточки поверху " + floor + ". Аркуші: " + sheetIndexes + "."
                + (roomNames == null || roomNames.isEmpty() ? ""
                : " Кімнати поверху: " + String.join(", ", roomNames) + ".");
        String json = requestJson(albumPdf, POINTS_PROMPT, task, AlbumSchemas.POINTS);
        return read(json, PointsResult.class);
    }

    /** Stage 2C: light fixtures + switching groups. */
    public LightingResult extractLighting(byte[] albumPdf, List<Integer> sheetIndexes) {
        String task = "Витягни світильники і групи світла. Аркуші: " + sheetIndexes + ".";
        String json = requestJson(albumPdf, LIGHTING_PROMPT, task, AlbumSchemas.LIGHTING);
        return read(json, LightingResult.class);
    }

    /** Stage 2D: floor heating, AC/vent power points, panel location. */
    public HeatingResult extractHeating(byte[] albumPdf, List<Integer> sheetIndexes) {
        String task = "Витягни теплу підлогу, кондиціонери/вентилятори і місце щита. Аркуші: "
                + sheetIndexes + ".";
        String json = requestJson(albumPdf, HEATING_PROMPT, task, AlbumSchemas.HEATING);
        return read(json, HeatingResult.class);
    }

    // ---- Anthropic round-trip ----------------------------------------------------

    /**
     * One structured-output pass over the album: shared system prompt (cached) + domain
     * prompt, PDF document block first, then the dynamic task text. Returns the JSON text
     * of the first text block after checking {@code stop_reason}.
     */
    String requestJson(byte[] albumPdf, String domainPrompt, String task, Map<String, Object> schema) {
        if (!props.isConfigured()) {
            throw new AiExtractionException("error.ai.unavailable");
        }
        Map<String, Object> resp;
        try {
            resp = postForMap(buildBody(albumPdf, domainPrompt, task, schema));
        } catch (Exception e) {
            log.error("Anthropic album pass failed: {}", e.getMessage());
            throw new AiExtractionException("error.ai.unavailable", e);
        }
        checkStopReason(resp);
        return firstTextBlock(resp);
    }

    /** Request body — package-private so tests can assert the exact wire shape. */
    Map<String, Object> buildBody(byte[] albumPdf, String domainPrompt, String task,
                                  Map<String, Object> schema) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("max_tokens", Math.max(props.maxTokens(), MIN_MAX_TOKENS));
        // Opus 4.7+: adaptive thinking; effort lives in output_config. No temperature.
        body.put("thinking", Map.of("type", "adaptive"));
        body.put("output_config", Map.of(
                "effort", "high",
                "format", Map.of("type", "json_schema", "schema", schema)));
        // System as a block array: the shared prompt carries cache_control so passes
        // 2..N over the same album hit the prompt cache (prefix = system + document).
        body.put("system", List.of(
                Map.of("type", "text", "text", COMMON_PROMPT,
                        "cache_control", Map.of("type", "ephemeral")),
                Map.of("type", "text", "text", domainPrompt)));
        // Document first, dynamic task text after — keeps the cacheable prefix stable.
        Map<String, Object> document = Map.of(
                "type", "document",
                "source", Map.of("type", "base64", "media_type", "application/pdf",
                        "data", Base64.getEncoder().encodeToString(albumPdf)));
        body.put("messages", List.of(Map.of("role", "user", "content",
                List.of(document, Map.of("type", "text", "text", task)))));
        return body;
    }

    /**
     * POST with a few quick retries on transient failures (429 / 5xx / dropped connection).
     * Package-private + overridable so tests stub the HTTP hop without a server.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> postForMap(Map<String, Object> body) {
        for (int attempt = 1; ; attempt++) {
            try {
                return restClient.post()
                        .uri(MESSAGES_URL)
                        .header("x-api-key", props.apiKey())
                        .header("anthropic-version", ANTHROPIC_VERSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(Map.class);
            } catch (RestClientResponseException e) {
                if (attempt >= MAX_ATTEMPTS || !isTransient(e.getStatusCode().value())) {
                    throw e;
                }
                backoff(attempt, e);
            } catch (ResourceAccessException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                backoff(attempt, e);
            }
        }
    }

    /** Transient = worth retrying: 429 (rate limit) or any 5xx (incl. 529 "Overloaded"). */
    static boolean isTransient(int status) {
        return status == 429 || status >= 500;
    }

    private static void backoff(int attempt, RuntimeException cause) {
        log.warn("Anthropic album pass transient failure (attempt {}/{}), retrying: {}",
                attempt, MAX_ATTEMPTS, cause.getMessage());
        try {
            Thread.sleep(BACKOFF_BASE_MS * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw cause;
        }
    }

    /**
     * {@code max_tokens} means the JSON was truncated (caller should raise the cap, not
     * parse garbage); {@code refusal} is a safety decline — both are explicit failures.
     */
    static void checkStopReason(Map<String, Object> resp) {
        Object stop = resp == null ? null : resp.get("stop_reason");
        if ("max_tokens".equals(stop)) {
            throw new AiExtractionException("error.ai.response-truncated");
        }
        if ("refusal".equals(stop)) {
            throw new AiExtractionException("error.ai.refused");
        }
    }

    @SuppressWarnings("unchecked")
    private String firstTextBlock(Map<String, Object> resp) {
        Object content = resp == null ? null : resp.get("content");
        if (content instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> map && "text".equals(map.get("type"))) {
                    Object text = map.get("text");
                    if (text instanceof String s && !s.isBlank()) {
                        return s;
                    }
                }
            }
        }
        throw new AiExtractionException("error.ai.unavailable");
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.error("Failed to parse album extraction JSON into {}: {}",
                    type.getSimpleName(), e.getMessage());
            throw new AiExtractionException("error.ai.unavailable", e);
        }
    }
}
