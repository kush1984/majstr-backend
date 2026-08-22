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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a receipt photo for the act's «Чеки та рахунки» block (act-receipts round 2). Two depths:
 *
 * <ul>
 *   <li>{@link #extractMeta} — DATE + TOTAL + a short label, three fields off a printed footer.
 *       Runs on {@link AiFlow#ACT_RECEIPT} (a small model by default) because this happens on every
 *       receipt the master attaches.</li>
 *   <li>{@link #extractWithItems} — the same meta PLUS every purchased position, for «перенести
 *       позиції у акт». Runs on {@link AiFlow#RECEIPT} under
 *       {@link EstimateExtractor#RECEIPT_SYSTEM_PROMPT} — the estimate import's own receipt prompt,
 *       reused verbatim with a short tail for the footer fields.</li>
 * </ul>
 *
 * <p>That reuse is the point, not an implementation detail (round-2 fix, master report: the act read
 * no positions off a receipt the estimate import reads fine). This class used to carry a shorter
 * "and also list the items" prompt of its own; a Ukrainian fiscal receipt's table is not a job to
 * describe twice, and the estimate's version is the one tuned against real paper — the «#article»
 * line as the per-item anchor, the 3-line layout, the "never stop after the first few" rule, the
 * unit read from the parentheses. One prompt, one behaviour, wherever the master reads a receipt.</p>
 *
 * <p>Failures are the caller's to soften: this component throws like every extractor, and
 * {@code WorkActReceiptService.recognize} turns that into a «не розпізнано — введіть вручну»
 * response instead of an error, because a receipt the model cannot read is still a receipt the
 * master can type.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActReceiptExtractor {

    // Same plain-typed schema style as EstimateExtractor: "unreadable" is a sentinel the prompt
    // spells out (empty string / 0), turned back into null by parse() — not a nullable union type.
    private static final Map<String, Object> STRING = Map.of("type", "string");
    private static final Map<String, Object> NUMBER = Map.of("type", "number");

    /** Date shapes a Ukrainian receipt actually prints, beyond the ISO the prompt asks for. A model
     *  that hands back the paper's own «04.06.2026» read it correctly — dropping that on a format
     *  detail would be our bug, not its. Two-digit years resolve to 20YY. */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yy"),
            DateTimeFormatter.ofPattern("dd/MM/yy"),
            DateTimeFormatter.ofPattern("dd-MM-yy"));

    private final AiExtractors extractors;
    private final ObjectMapper objectMapper;

    /** Everything a recognition pass may return; {@code items} is empty in meta-only mode. */
    public record Recognized(String label, LocalDate issuedAt, BigDecimal total,
                             List<EstimateExtractor.Extracted.Line> items) {}

    public Recognized extractMeta(String mediaType, byte[] bytes) {
        String json = extractors.forFlow(AiFlow.ACT_RECEIPT).requestJson(
                AiInput.image(mediaType, bytes, "Read this receipt's total, date and issuer."),
                META_PROMPT, metaSchema());
        return parse(json);
    }

    public Recognized extractWithItems(String mediaType, byte[] bytes) {
        String json = extractors.forFlow(AiFlow.RECEIPT).requestJson(
                AiInput.image(mediaType, bytes,
                        "Extract the purchased items from this receipt photo, plus its issuer, "
                                + "date and total."),
                EstimateExtractor.RECEIPT_SYSTEM_PROMPT + FOOTER_TAIL, fullSchema());
        return parse(json);
    }

    // ---- parsing ----------------------------------------------------------

    @SuppressWarnings("unchecked")
    Recognized parse(String json) { // package-private for the unit test, like EstimateExtractor
        try {
            Map<String, Object> root = objectMapper.readValue(json, Map.class);
            List<EstimateExtractor.Extracted.Line> lines = new ArrayList<>();
            if (root.get("items") instanceof List<?> arr) {
                for (Object element : arr) {
                    if (!(element instanceof Map<?, ?> map)) {
                        continue;
                    }
                    String name = str(map.get("name"));
                    if (name == null) {
                        continue;
                    }
                    lines.add(new EstimateExtractor.Extracted.Line(
                            name, str(map.get("unit")), bd(map.get("quantity")),
                            bd(map.get("unitPrice")), str(map.get("type")), str(map.get("category"))));
                }
            }
            BigDecimal total = bd(root.get("total"));
            if (total != null && total.signum() <= 0) {
                total = null; // 0 = the prompt's "unreadable" sentinel
            }
            return new Recognized(str(root.get("label")), date(str(root.get("issuedAt"))),
                    total, lines);
        } catch (Exception e) {
            log.warn("Act receipt recognition JSON unusable: {}", e.getMessage());
            throw new AiExtractionException("error.ai.unavailable", e);
        }
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                LocalDate parsed = LocalDate.parse(value, format);
                // A receipt is never from the future — that is a mis-read year, not a purchase.
                // An OLD one is kept on purpose (round-2 fix): the paper a master photographs can be
                // months or years old, the value only PREFILLS a field they see and correct before
                // «Додати чек», and blanking a date the model read correctly taught them that the
                // recognition "doesn't take dates at all".
                return parsed.isAfter(LocalDate.now()) ? null : parsed;
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        return null;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal bd(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString().replace(',', '.').trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- prompts + schemas ------------------------------------------------

    /** The three footer fields, worded once and appended to both prompts — the act needs them at
     *  either depth, while the estimate import (which owns the table half) never asks for them. */
    private static final String FOOTER_FIELDS = """
              - label: WHO issued it — the store/company name from the header («Епіцентр»,
                «Нова Пошта», ФОП name), short, as printed. An empty string "" if unreadable.
              - issuedAt: the receipt's own date as ISO "YYYY-MM-DD". Ukrainian receipts print it
                DD.MM.YYYY, and cash registers often print a footer line "DD-MM-YY HH:MM:SS № NNNN"
                just above ФН / «ФІСКАЛЬНИЙ ЧЕК» — in that footer the FIRST pair is the day and the
                two-digit year is 20YY. Convert whatever you find. Return "" only when no date is
                printed at all or the digits are unreadable: a date you can read is wanted even if
                the receipt is years old.
              - total: the FINAL amount paid, as a number with kopecks (e.g. 483.50). Look for
                «СУМА», «ДО СПЛАТИ», «ВСЬОГО», the card-slip amount, or the largest bottom figure.
                Never invent it: 0 if unreadable.""";

    private static final String META_PROMPT = """
            You read a photo of a Ukrainian retail receipt (фіскальний чек), a card-terminal slip,
            an invoice (рахунок) or a hand-written note. Return:
            """ + FOOTER_FIELDS + """

            Use the sentinels ("" / 0) for anything you cannot read confidently — a wrong figure is
            worse than an empty field the user fills by hand.""";

    private static final String FOOTER_TAIL = """


            ADDITIONALLY, alongside the items, return the receipt's own three header/footer fields:
            """ + FOOTER_FIELDS;

    private static Map<String, Object> metaSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("label", STRING);
        properties.put("issuedAt", STRING);
        properties.put("total", NUMBER);
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("label", "issuedAt", "total"),
                "properties", properties);
    }

    /** The estimate receipt schema (items + depositAmount, which its prompt still asks for) with the
     *  three footer fields added — the prompt is that one, so the schema must match it. */
    private static Map<String, Object> fullSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("items", Map.of("type", "array", "items", EstimateExtractor.lineSchema()));
        properties.put("depositAmount", NUMBER);
        properties.put("label", STRING);
        properties.put("issuedAt", STRING);
        properties.put("total", NUMBER);
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("items", "depositAmount", "label", "issuedAt", "total"),
                "properties", properties);
    }
}
