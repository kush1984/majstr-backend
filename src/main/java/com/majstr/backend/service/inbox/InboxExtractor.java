package com.majstr.backend.service.inbox;

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
import java.util.Locale;
import java.util.Map;

/**
 * A bot or quick-add message → the things it asks for, each with the object it names.
 *
 * <p><b>The model only reads; it never decides what is final.</b> It is shown the master's ACTIVE
 * objects as a numbered list and answers with a number, plus whether the text actually NAMED that
 * object ({@code objectMentioned}). {@link InboxService} files an item by itself only when the object
 * was named; a guess is turned into a question. Prices for estimate lines are never the model's —
 * {@code CatalogMatcher} prices them from the master's own catalog, as dictation does.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InboxExtractor {

    private static final Map<String, Object> STRING = Map.of("type", "string");
    private static final Map<String, Object> NUMBER = Map.of("type", "number");
    private static final Map<String, Object> INTEGER = Map.of("type", "integer");
    private static final Map<String, Object> BOOLEAN = Map.of("type", "boolean");

    private final AiExtractors extractors;
    private final ObjectMapper objectMapper;

    /** An object as the model sees it: 1-based number, name, address, client. */
    public record ObjectRef(int number, String name, String address, String client) {}

    /** One thing the message asks for, raw — normalised by the service. Absent values are null. */
    public record Parsed(String kind, int object, boolean objectMentioned, String text,
                         BigDecimal quantity, String unit, BigDecimal unitPrice, BigDecimal amount,
                         String category) {}

    public List<Parsed> extract(String message, List<ObjectRef> objects) {
        String json = extractors.forFlow(AiFlow.INBOX)
                .requestJson(AiInput.text(input(message, objects)), PROMPT, schema());
        return parse(json, objects.size());
    }

    static String input(String message, List<ObjectRef> objects) {
        StringBuilder sb = new StringBuilder("OBJECTS:\n");
        if (objects.isEmpty()) {
            sb.append("(none)\n");
        }
        for (ObjectRef o : objects) {
            sb.append(o.number()).append(". ").append(o.name());
            if (o.address() != null) {
                sb.append(" — адреса: ").append(o.address());
            }
            if (o.client() != null) {
                sb.append(" — клієнт: ").append(o.client());
            }
            sb.append('\n');
        }
        return sb.append("\nMESSAGE:\n").append(message.trim()).toString();
    }

    @SuppressWarnings("unchecked")
    List<Parsed> parse(String json, int objectCount) { // package-private for the unit test
        try {
            Map<String, Object> root = objectMapper.readValue(json, Map.class);
            if (!(root.get("items") instanceof List<?> list)) {
                return List.of();
            }
            List<Parsed> out = new ArrayList<>(list.size());
            for (Object raw : list) {
                if (!(raw instanceof Map<?, ?> map)) {
                    continue;
                }
                String text = str(map.get("text"));
                String kind = str(map.get("kind"));
                if (text == null || kind == null) {
                    continue;
                }
                int object = map.get("object") instanceof Number n ? n.intValue() : 0;
                if (object < 0 || object > objectCount) {
                    object = 0; // a number outside the list names nothing
                }
                boolean mentioned = object > 0 && Boolean.TRUE.equals(map.get("objectMentioned"));
                out.add(new Parsed(kind.toUpperCase(Locale.ROOT), object, mentioned, text,
                        positive(map.get("quantity"), MAX_NUMBER), str(map.get("unit")),
                        positive(map.get("unitPrice"), MAX_MONEY), positive(map.get("amount"), MAX_MONEY),
                        str(map.get("category"))));
            }
            return out;
        } catch (Exception e) {
            log.warn("Inbox JSON unusable: {}", e.getMessage());
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

    static final BigDecimal MAX_NUMBER = new BigDecimal("999999999");
    /** The ceiling every money form in the app has ({@code @DecimalMax("100000000")}). */
    static final BigDecimal MAX_MONEY = new BigDecimal("100000000");

    private static BigDecimal positive(Object value, BigDecimal max) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal parsed = new BigDecimal(value.toString().replace(',', '.').trim());
            // A number the columns cannot hold (NUMERIC(15,…)) is a misreading, not a figure (review M-3).
            return parsed.signum() > 0 && parsed.compareTo(max) <= 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final String PROMPT = """
            A Ukrainian construction contractor sends a short note to his own assistant from a site or
            from the car — typed or DICTATED with the phone keyboard, so expect no punctuation, spoken
            numerals («три» = 3, «півтори тисячі» = 1500), filler words and self-corrections (keep the
            corrected value). You get his ACTIVE OBJECTS as a numbered list, then the MESSAGE.

            Split the message into separate things he wants recorded. One item per thing, in order:
              - kind:
                  "SHOPPING"      — something to buy or bring for an object («докупити 3 мішки клею»).
                  "TASK"          — something to do («замовити двері», «подзвонити електрику»).
                  "NOTE"          — a fact to remember, not an action (a door code, a phone number,
                                    what the client said).
                  "ESTIMATE_LINE" — a work or material to ADD TO THE ESTIMATE / кошторис («додати в
                                    кошторис демонтаж плитки 12 квадратів», «дописати ще 8 точок»).
                  "EXPENSE"       — money he SPENT or paid out («дав Василю 2000», «заправився на 1500»,
                                    «купив профіль на 3400»).
                  "INCOME"        — money he RECEIVED («Ігор віддав 10 тисяч», «отримав аванс 20000»).
                A purchase with an amount already PAID is an EXPENSE; without a paid amount it is SHOPPING.
              - object: the NUMBER of the object from the list this item is about, or 0 when it is about
                none or you cannot tell. Objects are named loosely — by street («Бандери»), district
                («Сихів»), client's name («у Ігоря»), or a nickname; match those against the list.
                When the message names one object once, the following items are about it too.
              - objectMentioned: true ONLY when the message itself names this object (directly or by
                that carry-over). false when you merely guessed — e.g. there is a single object and the
                message does not name it. Never true when object is 0.
              - text: the thing itself in his own words, WITHOUT the object, the quantity, the unit or
                the amount («клей для плитки», «замовити двері», «демонтаж плитки»). For a NOTE keep
                the whole fact including numbers. For money, what it was for («плитка», «пальне»,
                «аванс»); if nothing was said, a short neutral word («витрата», «оплата»).
              - quantity: how many, for SHOPPING and ESTIMATE_LINE. 0 when not said.
              - unit: "м2", "м.п.", "м", "шт", "кг", "т", "м3", "л", "год", "компл", "точка", "день",
                "мішок" → "шт". "" when not said.
              - unitPrice: a price PER UNIT he actually said for an ESTIMATE_LINE. 0 otherwise — never
                invent one.
              - amount: the money for EXPENSE / INCOME, in hryvnia. 0 when not said.
              - category: for EXPENSE one of "MATERIALS", "CREW" (paid workers/helpers), "FUEL",
                "TOOLS", "TAXES", "OTHER"; for INCOME "ADVANCE" (an advance/prepayment) or "WORK";
                "" for everything else.

            Ignore greetings and chatter. If nothing in the message is a thing to record, return an
            empty items list. Use the sentinels (0 / "") for whatever was not said — an invented value
            ends up in his money or in a document his client signs.""";

    private static Map<String, Object> schema() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", STRING);
        item.put("object", INTEGER);
        item.put("objectMentioned", BOOLEAN);
        item.put("text", STRING);
        item.put("quantity", NUMBER);
        item.put("unit", STRING);
        item.put("unitPrice", NUMBER);
        item.put("amount", NUMBER);
        item.put("category", STRING);
        Map<String, Object> itemSchema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.copyOf(item.keySet()),
                "properties", item);
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("items"),
                "properties", Map.of("items", Map.of("type", "array", "items", itemSchema)));
    }
}
