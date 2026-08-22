package com.majstr.backend.service.importer;

import com.majstr.backend.exception.AiExtractionException;
import com.majstr.backend.service.ai.AiExtractors;
import com.majstr.backend.service.ai.AiFlow;
import com.majstr.backend.service.ai.AiInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What to ask about an estimate or a receipt, and how to read the answer — with no idea who
 * answered it.
 *
 * <p>This class used to BE the Anthropic client as well ({@code ClaudeEstimateExtractor}), and that
 * is precisely why estimate and receipt import could not follow {@code app.ai.provider} while the
 * measurement flows already did: the prompts came welded to one vendor's HTTP. The transport now
 * lives in {@code AnthropicJsonExtractor} / {@code OpenAiJsonExtractor} behind
 * {@code JsonExtractor}, and what is left here is the domain knowledge: two prompts, one schema,
 * and the parsing of the result.</p>
 *
 * <p>It returns raw strings/numbers as the model gave them; unit/type normalisation and
 * issue-flagging happen in {@code EstimateImportService}. Any failure (unconfigured key, HTTP
 * error, unparseable response) becomes an {@link AiExtractionException} — the import is synchronous,
 * so it is surfaced to the master, not logged-and-skipped.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EstimateExtractor {

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
    /** Shared with {@link ActReceiptExtractor}: the act reads a receipt with the SAME prompt, plus a
     *  tail asking for the footer meta. One description of "read this receipt table", not two. */
    static final String RECEIPT_SYSTEM_PROMPT = """
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
    /**
     * Two flows live in this one class: the estimate prompts and the receipt one. They are read
     * separately because they are different jobs — a receipt is a small printed table read many
     * times a day, an estimate is a whole document read occasionally — and a master may well want
     * a cheaper model on the frequent one.
     */
    private final AiExtractors extractors;
    private final ObjectMapper objectMapper;

    /** Extract from a spreadsheet/CSV already rendered to a plain text grid. */
    public Extracted extractFromText(String grid) {
        String instruction = "Extract the estimate positions from this spreadsheet grid:\n\n" + grid;
        return call(AiFlow.ESTIMATE, AiInput.text(instruction), SYSTEM_PROMPT);
    }

    /** Extract from a photo (printed or hand-written). {@code mediaType} e.g. image/jpeg. */
    public Extracted extractFromImage(String mediaType, byte[] bytes) {
        return call(AiFlow.ESTIMATE,
                AiInput.image(mediaType, bytes, "Extract the estimate positions from this photo."),
                SYSTEM_PROMPT);
    }

    /** Extract purchased items from a receipt photo (store / terminal / hand-written). */
    public Extracted extractReceiptFromImage(String mediaType, byte[] bytes) {
        return call(AiFlow.RECEIPT,
                AiInput.image(mediaType, bytes, "Extract the purchased items from this receipt photo."),
                RECEIPT_SYSTEM_PROMPT);
    }

    private Extracted call(AiFlow flow, List<AiInput> content, String systemPrompt) {
        return parse(flow, extractors.forFlow(flow).requestJson(content, systemPrompt, SCHEMA));
    }

    @SuppressWarnings("unchecked")
    Extracted parse(AiFlow flow, String json) {
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
            log.error("Failed to parse extraction JSON from {}: {}",
                    extractors.forFlow(flow).providerName(), e.getMessage());
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

    /** Package-private: the act's receipt read reuses this line shape verbatim. */
    static Map<String, Object> lineSchema() {
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
