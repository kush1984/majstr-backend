package com.majstr.backend.service.importer;

import com.majstr.backend.config.AnthropicProperties;
import com.majstr.backend.service.importer.ClaudeEstimateExtractor.Extracted;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tests the JSON → {@link Extracted} mapping (the model's structured output),
 * without hitting the network. Locks the sentinel handling: an empty string reads as
 * null, a 0 deposit means "no deposit", and 0 quantity/price survive (flagged later on
 * the review screen). No Spring context — a plain {@link JsonMapper} is enough.
 */
class ClaudeEstimateExtractorTest {

    private final ClaudeEstimateExtractor extractor = new ClaudeEstimateExtractor(
            new AnthropicProperties("", "claude-opus-4-8", 8000), JsonMapper.builder().build());

    @Test
    void parsesItemsAndDeposit() {
        String json = """
                {
                  "items": [
                    {"name": "Малярні роботи", "unit": "м²", "quantity": 19.59, "unitPrice": 120, "type": "WORK", "category": "Кімната"},
                    {"name": "Клей", "unit": "шт", "quantity": 3, "unitPrice": 250.5, "type": "MATERIAL", "category": ""}
                  ],
                  "depositAmount": 5000
                }
                """;

        Extracted result = extractor.parse(json);

        assertThat(result.items()).hasSize(2);
        Extracted.Line first = result.items().get(0);
        assertThat(first.name()).isEqualTo("Малярні роботи");
        assertThat(first.unit()).isEqualTo("м²");
        assertThat(first.quantity()).isEqualByComparingTo("19.59");
        assertThat(first.unitPrice()).isEqualByComparingTo("120");
        assertThat(first.type()).isEqualTo("WORK");
        assertThat(first.category()).isEqualTo("Кімната");
        // Empty-string category → null.
        assertThat(result.items().get(1).category()).isNull();
        assertThat(result.depositAmount()).isEqualByComparingTo("5000");
    }

    @Test
    void treatsSentinelsAsUnreadableOrAbsent() {
        // Hand-written photo: an unreadable price (0) and unit ("") come back as sentinels;
        // no deposit is 0. The name-less row is dropped (a position must have a name).
        String json = """
                {
                  "items": [
                    {"name": "Демонтаж", "unit": "", "quantity": 0, "unitPrice": 0, "type": "WORK", "category": ""},
                    {"name": "", "unit": "шт", "quantity": 1, "unitPrice": 10, "type": "MATERIAL", "category": ""}
                  ],
                  "depositAmount": 0
                }
                """;

        Extracted result = extractor.parse(json);

        assertThat(result.items()).hasSize(1); // the name-less row is skipped
        Extracted.Line line = result.items().get(0);
        assertThat(line.unit()).isNull();                    // "" → null
        assertThat(line.quantity()).isEqualByComparingTo("0"); // 0 kept → flagged on review
        assertThat(line.unitPrice()).isEqualByComparingTo("0");
        assertThat(result.depositAmount()).isNull();          // 0 deposit → absent
    }

    @Test
    void retriesTransientStatusesButNotPermanentOnes() {
        // Transient — a quick retry is worth it (the master is waiting on a synchronous import).
        assertThat(ClaudeEstimateExtractor.isTransient(429)).isTrue(); // rate limit
        assertThat(ClaudeEstimateExtractor.isTransient(500)).isTrue();
        assertThat(ClaudeEstimateExtractor.isTransient(503)).isTrue();
        assertThat(ClaudeEstimateExtractor.isTransient(529)).isTrue(); // Anthropic "Overloaded"
        // Permanent — retrying can't help; fail fast to the manual-entry fallback.
        assertThat(ClaudeEstimateExtractor.isTransient(400)).isFalse(); // bad request
        assertThat(ClaudeEstimateExtractor.isTransient(401)).isFalse(); // bad key
        assertThat(ClaudeEstimateExtractor.isTransient(413)).isFalse(); // payload too large
        assertThat(ClaudeEstimateExtractor.isTransient(404)).isFalse();
    }
}
