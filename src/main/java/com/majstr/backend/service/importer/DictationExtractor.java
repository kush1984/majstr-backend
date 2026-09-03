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
 * Free text → spoken estimate positions. The only extractor here that reads no image: the master
 * types, or holds the keyboard's microphone button, and the text arrives as text.
 *
 * <p><b>It reads the sentence, it does not price it.</b> A spoken price is carried through when it
 * was actually said, but the usual case is «поклеїти шпалери двадцять квадратів» with no number at
 * all — the price and the unit then come from the master's own catalog, deterministically, in
 * {@link CatalogMatcher}. Sending the catalog into the prompt was considered and rejected for cut 0:
 * a full catalog is ~900 names, it would cost more than the sentence being read, and a model that
 * can see the list will match something to every line — the exact failure this feature must not
 * have, since an unmatched position has to be visible.</p>
 *
 * <p>Failures throw, like every extractor; the caller decides how loud that is.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DictationExtractor {

    // Same plain-typed schema style as the other extractors: "not said" is a sentinel the prompt
    // spells out (empty string / 0), turned back into null by parse() — not a nullable union type.
    private static final Map<String, Object> STRING = Map.of("type", "string");
    private static final Map<String, Object> NUMBER = Map.of("type", "number");

    private final AiExtractors extractors;
    private final ObjectMapper objectMapper;

    /** One position as it was spoken — raw strings, normalized downstream like every other flow. */
    public record Spoken(String name, String unit, BigDecimal quantity, BigDecimal unitPrice, String type) {}

    public List<Spoken> extract(String text) {
        String json = extractors.forFlow(AiFlow.DICTATION).requestJson(
                AiInput.text(text), PROMPT, schema());
        return parse(json);
    }

    // ---- parsing ----------------------------------------------------------

    @SuppressWarnings("unchecked")
    List<Spoken> parse(String json) { // package-private for the unit test, like the other extractors
        try {
            Map<String, Object> root = objectMapper.readValue(json, Map.class);
            Object rawItems = root.get("items");
            if (!(rawItems instanceof List<?> list)) {
                return List.of();
            }
            List<Spoken> items = new ArrayList<>(list.size());
            for (Object raw : list) {
                if (!(raw instanceof Map<?, ?> map)) {
                    continue;
                }
                String name = str(map.get("name"));
                if (name == null) {
                    continue; // a row with no name is not a position, whatever else it carries
                }
                items.add(new Spoken(name, str(map.get("unit")), positive(map.get("quantity")),
                        positive(map.get("unitPrice")), str(map.get("type"))));
            }
            return items;
        } catch (Exception e) {
            log.warn("Dictation JSON unusable: {}", e.getMessage());
            throw new AiExtractionException("error.ai.unavailable", e);
        }
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** A number, or null for the prompt's "not said" sentinel — 0 and negatives both mean absent. */
    private static BigDecimal positive(Object value) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal parsed = new BigDecimal(value.toString().replace(',', '.').trim());
            return parsed.signum() > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- prompt + schema --------------------------------------------------

    private static final String PROMPT = """
            You read a short Ukrainian text in which a construction contractor lists the positions
            of an estimate. It is usually DICTATED, so expect no punctuation, spoken numerals
            («двадцять пʼять» = 25, «півтора» = 1.5), filler words and self-corrections — when the
            speaker corrects himself, keep only the corrected value.

            Return one item per position, in the order they were said:
              - name: the work or material itself, WITHOUT the quantity, the unit or the price.
                Keep the speaker's own words («поклеїти шпалери», «штукатурка стін по маяках») —
                do not translate, do not invent a more official wording, do not add anything he
                did not say. This name is matched against his own price list afterwards.
              - unit: the unit as an abbreviation — "м2", "м.п.", "м", "шт", "кг", "т", "м3",
                "год", "компл", "точка", "день". An empty string "" when no unit was said.
                «квадратів»/«квадратних метрів» = "м2", «погонних» = "м.п.", «штук» = "шт".
              - quantity: how many, as a number (25, 1.5). 0 when no quantity was said.
              - unitPrice: the price PER UNIT in hryvnia, and ONLY when he said one («по 250
                гривень за квадрат»). 0 otherwise. Never invent a price, and never divide a total
                by a quantity to obtain one — his own price list fills this in.
              - type: "WORK" for a job done, "MATERIAL" for goods bought. When unclear, "WORK" —
                a contractor dictating an estimate is normally listing his work.

            Ignore anything that is not a position: greetings, an address, a client's name, notes
            about timing. If the text contains no position at all, return an empty items list.
            Use the sentinels ("" / 0) for whatever was not said — a value you invent becomes a
            number the client signs.""";

    private static Map<String, Object> schema() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", STRING);
        item.put("unit", STRING);
        item.put("quantity", NUMBER);
        item.put("unitPrice", NUMBER);
        item.put("type", STRING);
        Map<String, Object> itemSchema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("name", "unit", "quantity", "unitPrice", "type"),
                "properties", item);
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("items"),
                "properties", Map.of("items", Map.of("type", "array", "items", itemSchema)));
    }
}
