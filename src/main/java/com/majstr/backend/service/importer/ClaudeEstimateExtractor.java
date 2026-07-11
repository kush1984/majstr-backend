package com.majstr.backend.service.importer;

import com.majstr.backend.config.AnthropicProperties;
import com.majstr.backend.exception.AiExtractionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
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
        return call(List.of(Map.of("type", "text", "text", instruction)));
    }

    /** Extract from a photo (printed or hand-written). {@code mediaType} e.g. image/jpeg. */
    public Extracted extractFromImage(String mediaType, byte[] bytes) {
        String base64 = Base64.getEncoder().encodeToString(bytes);
        Map<String, Object> image = Map.of(
                "type", "image",
                "source", Map.of("type", "base64", "media_type", mediaType, "data", base64));
        Map<String, Object> text = Map.of(
                "type", "text",
                "text", "Extract the estimate positions from this photo.");
        return call(List.of(image, text));
    }

    // ---- Anthropic round-trip --------------------------------------------------

    @SuppressWarnings("unchecked")
    private Extracted call(List<Map<String, Object>> content) {
        if (!props.isConfigured()) {
            throw new AiExtractionException("error.ai.unavailable");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("max_tokens", props.maxTokens());
        body.put("system", SYSTEM_PROMPT);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        body.put("output_config", Map.of("format",
                Map.of("type", "json_schema", "schema", SCHEMA)));

        Map<String, Object> resp;
        try {
            resp = restClient.post()
                    .uri(MESSAGES_URL)
                    .header("x-api-key", props.apiKey())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.error("Anthropic extraction call failed: {}", e.getMessage());
            throw new AiExtractionException("error.ai.unavailable", e);
        }
        return parse(firstTextBlock(resp));
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
