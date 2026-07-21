package com.majstr.backend.service.importer;

import com.majstr.backend.config.AnthropicProperties;
import com.majstr.backend.exception.AiExtractionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts estimate line items from a text grid (Excel/CSV rendered to text) or a
 * photo via the Anthropic Messages API — raw HTTP ({@link RestClient}), matching the
 * codebase's no-SDK precedent ({@code ResendEmailService}, {@code MonobankClient}).
 * Model + key come from {@link AnthropicProperties} (key env only). Structured JSON
 * output is enforced with {@code output_config.format} (a JSON schema), so the first
 * text block of the response is the parseable result. No beta headers — vision and
 * structured outputs are GA on the Opus tier.
 *
 * <p>The extractor is purely the Claude round-trip: it returns raw strings/numbers;
 * unit/type normalization and issue-flagging happen in {@code EstimateImportService}.
 * Any failure (unconfigured key, HTTP error, unparseable response) becomes an
 * {@link AiExtractionException} — the import is synchronous, so it is surfaced to the
 * master, not logged-and-skipped.</p>
 */
@Slf4j
@Component
public class ClaudeEstimateExtractor {

    private static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    // Import is synchronous (the master is waiting), so retries are FEW and quick — just enough
    // to ride out a transient Anthropic hiccup (529 "Overloaded", 429, a 5xx, or a dropped
    // connection) instead of dropping the master to manual entry on the first blip.
    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_BASE_MS = 400L;

    private static final String SYSTEM_PROMPT = """
            You extract line items from a Ukrainian building/renovation contractor's estimate.
            The input is either a spreadsheet rendered as a text grid, or a photo of an estimate
            (a printout OR a hand-written one). Return every work/material position:
              - name: the position text (Ukrainian), trimmed.
              - unit: the unit of measure as written (e.g. "м²", "м.пог.", "шт", "кг", "компл").
              - quantity: the amount as a number.
              - unitPrice: the price per unit in UAH as a number.
              - type: "WORK" for labour, "MATERIAL" for a consumable/material. Infer from the name.
              - category: the room/section header this row falls under, if any (else null).
            Also return depositAmount: the prepayment (завдаток) if the document states one, else null.

            Rules:
              - Do NOT invent positions or numbers. If a value is unreadable (common on hand-written
                photos), use 0 for an unreadable number and an empty string for unreadable text —
                never guess. A blurred digit → 0.
              - type must be exactly "WORK" or "MATERIAL".
              - category: use an empty string when the row has no section/room.
              - depositAmount: use 0 when the document states no prepayment.
              - Skip totals / subtotals / section-header rows (Разом, Всього, Загальна вартість, …) —
                those are not positions. A section header may still be used as a row's category.
              - Ignore the залишок / balance line — it is derived, not a position.
              - Keep the original order of the positions.
            """;

    /**
     * Receipt variant: extract purchased goods/services from a photo of a store, terminal,
     * or hand-written receipt — the lines are appended to an existing estimate (no deposit,
     * no catalog side-effect). Same output schema; depositAmount is unused (returns 0).
     */
    private static final String RECEIPT_SYSTEM_PROMPT = """
            You transcribe EVERY purchased item from a photo of a Ukrainian retail receipt
            (фіскальний чек) — a store/cash-register printout, a card-terminal slip, or a
            hand-written note. Be exhaustive: a receipt usually has MANY items (10, 20 or more).
            Do not stop after the first few — read the whole receipt top to bottom.

            LAYOUT of a Ukrainian fiscal receipt — each purchased item spans up to 3 printed lines:
              1) a quantity line "<QTY> x <UNIT_PRICE>"   e.g. "8 x 29,85"
              2) the item NAME, then its LINE TOTAL and a VAT letter (A/Б/…)
                                                          e.g. "Труба каналізаційна ПП   238,80 A"
              3) an article line starting with "#", with the unit in parentheses
                                                          e.g. "#70116191(шт.)"
            The name may wrap or be truncated on the printout — transcribe what is visible.
            IMPORTANT ANCHOR: there is exactly ONE item per "#"-article line. Count the "#..."
            lines and return the SAME number of items — one JSON item for each "#"-article line,
            in order, top to bottom. Duplicated products (the same name twice) are two items.

            For each item return:
              - name: the item text (Ukrainian, as printed), trimmed. Drop the "#code" itself.
              - unit: from the parentheses on the "#" line ("шт", "уп", "м", "кг", "л", "компл",
                "пач", "рул"); if none is shown, use "шт".
              - quantity: the <QTY> from the "N x price" line (default 1 if only a price is shown).
              - unitPrice: the <UNIT_PRICE> from the "N x price" line (the price PER UNIT, NOT the
                line total). If only a line total and a quantity are printed, divide to get it.
              - type: "MATERIAL" for goods (the usual case); "WORK" only for an explicit
                service/labour charge.
              - category: "" (receipts have no section headers).
            Also return depositAmount: 0.

            Rules:
              - Do NOT invent items or numbers. Unreadable number → 0, unreadable text → "".
                Never guess a blurred digit, but never skip an item that has a "#" line either.
              - Skip ONLY non-item lines: store name/address/ПН, каса/касир, date/time, "N x price"
                totals, ПДВ/tax, СУМА/РАЗОМ/ВСЬОГО, ГОТІВКОЮ/ЗДАЧА (cash/change), ФН/З.Н./ФІСКАЛЬНИЙ
                ЧЕК and other terminal metadata, loyalty/bonus lines.
              - type must be exactly "WORK" or "MATERIAL"; default "MATERIAL".
              - Keep the original top-to-bottom order.
            """;

    private final AnthropicProperties props;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();

    public ClaudeEstimateExtractor(AnthropicProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /** Extract from a spreadsheet/CSV already rendered to a plain text grid. */
    public Extracted extractFromText(String grid) {
        String instruction = "Extract the estimate positions from this spreadsheet grid:\n\n" + grid;
        return call(List.of(Map.of("type", "text", "text", instruction)), SYSTEM_PROMPT);
    }

    /** Extract from a photo (printed or hand-written). {@code mediaType} e.g. image/jpeg. */
    public Extracted extractFromImage(String mediaType, byte[] bytes) {
        return call(imageContent(mediaType, bytes, "Extract the estimate positions from this photo."),
                SYSTEM_PROMPT);
    }

    /** Extract purchased items from a receipt photo (store / terminal / hand-written). */
    public Extracted extractReceiptFromImage(String mediaType, byte[] bytes) {
        return call(imageContent(mediaType, bytes, "Extract the purchased items from this receipt photo."),
                RECEIPT_SYSTEM_PROMPT);
    }

    /**
     * One PDF document block + an instruction. Anthropic accepts a PDF natively (it renders
     * the pages itself), so an architect's plan needs no server-side rasterising — which
     * matters here because the deploy has no poppler.
     */
    public static List<Map<String, Object>> pdfContent(byte[] bytes, String instruction) {
        String base64 = Base64.getEncoder().encodeToString(bytes);
        Map<String, Object> doc = Map.of(
                "type", "document",
                "source", Map.of("type", "base64", "media_type", "application/pdf", "data", base64));
        return List.of(doc, Map.of("type", "text", "text", instruction));
    }

    /** Build a user-message content list of one image block + one instruction — reusable by
     *  any caller that needs a vision round-trip (estimate, receipt, sketch). */
    public static List<Map<String, Object>> imageContent(String mediaType, byte[] bytes, String instruction) {
        String base64 = Base64.getEncoder().encodeToString(bytes);
        Map<String, Object> image = Map.of(
                "type", "image",
                "source", Map.of("type", "base64", "media_type", mediaType, "data", base64));
        Map<String, Object> text = Map.of("type", "text", "text", instruction);
        return List.of(image, text);
    }

    // ---- Anthropic round-trip --------------------------------------------------

    private Extracted call(List<Map<String, Object>> content, String systemPrompt) {
        return parse(requestJson(content, systemPrompt, SCHEMA));
    }

    /**
     * The low-level Anthropic call: send {@code content} under {@code systemPrompt}, forcing
     * structured output to {@code schema} ({@code output_config.format}), and return the first
     * text block (the JSON string). Shared transport so a new extraction (e.g. room sketches)
     * reuses the ONE client/error handling with its own prompt + schema. Any failure →
     * {@link AiExtractionException} (surfaced synchronously, not logged-and-skipped).
     */
    @SuppressWarnings("unchecked")
    public String requestJson(List<Map<String, Object>> content, String systemPrompt,
                              Map<String, Object> schema) {
        if (!props.isConfigured()) {
            throw new AiExtractionException("error.ai.unavailable");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("max_tokens", props.maxTokens());
        body.put("system", systemPrompt);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        body.put("output_config", Map.of("format",
                Map.of("type", "json_schema", "schema", schema)));

        Map<String, Object> resp;
        try {
            resp = postForMap(body);
        } catch (Exception e) {
            log.error("Anthropic extraction call failed: {}", e.getMessage());
            throw new AiExtractionException("error.ai.unavailable", e);
        }
        return firstTextBlock(resp);
    }

    /**
     * POST the request, retrying up to {@link #MAX_ATTEMPTS} times on a TRANSIENT failure
     * (see {@link #isTransient}) with a short linear backoff. A permanent 4xx (bad request,
     * bad key, payload too large) is not retried. On exhaustion the last exception propagates
     * and becomes a 503 {@code AI_UNAVAILABLE} upstream — same fallback as before, just after
     * a couple of quick retries rather than on the first blip.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> postForMap(Map<String, Object> body) {
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
            } catch (RestClientResponseException e) { // carries the HTTP status
                if (attempt >= MAX_ATTEMPTS || !isTransient(e.getStatusCode().value())) {
                    throw e;
                }
                backoff(attempt, e);
            } catch (ResourceAccessException e) { // connection reset / read timeout
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                backoff(attempt, e);
            }
        }
    }

    /** Transient = worth retrying: 429 (rate limit), or any 5xx (incl. 529 "Overloaded"). */
    static boolean isTransient(int status) {
        return status == 429 || status >= 500;
    }

    private static void backoff(int attempt, RuntimeException cause) {
        log.warn("Anthropic call transient failure (attempt {}/{}), retrying: {}",
                attempt, MAX_ATTEMPTS, cause.getMessage());
        try {
            Thread.sleep(BACKOFF_BASE_MS * attempt); // 400ms, 800ms — the master is waiting
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw cause; // give up promptly if the request thread is interrupted
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

    @SuppressWarnings("unchecked")
    Extracted parse(String json) {
        try {
            Map<String, Object> root = objectMapper.readValue(json, Map.class);
            List<Extracted.Line> lines = new ArrayList<>();
            if (root.get("items") instanceof List<?> arr) {
                for (Object element : arr) {
                    if (!(element instanceof Map<?, ?> map)) {
                        continue;
                    }
                    String name = str(map.get("name"));
                    if (name == null) {
                        continue; // a position must have a name
                    }
                    lines.add(new Extracted.Line(
                            name,
                            str(map.get("unit")),
                            bd(map.get("quantity")),
                            bd(map.get("unitPrice")),
                            str(map.get("type")),
                            str(map.get("category"))));
                }
            }
            BigDecimal deposit = bd(root.get("depositAmount"));
            if (deposit != null && deposit.signum() == 0) {
                deposit = null; // 0 = "no deposit" sentinel → absent
            }
            return new Extracted(lines, deposit);
        } catch (Exception e) {
            log.error("Failed to parse Anthropic extraction JSON: {}", e.getMessage());
            throw new AiExtractionException("error.ai.unavailable", e);
        }
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    /** JSON number (Integer/Long/Double/BigDecimal) or numeric string → BigDecimal, else null.
     *  {@code String.valueOf(Number)} uses the shortest round-trip form, so no float artifacts
     *  for realistic values; an unreadable value stays null and is flagged on the review screen. */
    private static BigDecimal bd(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String s = String.valueOf(value).trim();
            return s.isEmpty() ? null : new BigDecimal(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Raw extraction result — strings/numbers as the model returned them. */
    public record Extracted(List<Line> items, BigDecimal depositAmount) {
        public record Line(
                String name,
                String unit,
                BigDecimal quantity,
                BigDecimal unitPrice,
                String type,
                String category
        ) {}
    }

    // ---- JSON schema for output_config.format ---------------------------------
    // Every object needs additionalProperties:false and lists ALL properties in
    // required (the widely-supported structured-outputs shape). Numeric constraints
    // (minimum/maximum) are unsupported, so the schema stays plain and the model uses
    // sentinels (0 / "") for unreadable values, mapped to null + a review flag server-side.

    private static final Map<String, Object> STRING = Map.of("type", "string");
    private static final Map<String, Object> NUMBER = Map.of("type", "number");

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", List.of("items", "depositAmount"),
            "properties", Map.of(
                    "items", Map.of(
                            "type", "array",
                            "items", lineSchema()),
                    "depositAmount", NUMBER));

    private static Map<String, Object> lineSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", STRING);
        properties.put("unit", STRING);
        properties.put("quantity", NUMBER);
        properties.put("unitPrice", NUMBER);
        properties.put("type", STRING);
        properties.put("category", STRING);
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("name", "unit", "quantity", "unitPrice", "type", "category"),
                "properties", properties);
    }
}
