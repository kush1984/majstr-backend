package com.majstr.backend.service.importer;

import com.majstr.backend.config.AiFlowsProperties;
import com.majstr.backend.config.AnthropicProperties;
import com.majstr.backend.config.OpenAiProperties;
import com.majstr.backend.exception.AiExtractionException;
import com.majstr.backend.service.ai.AiExtractors;
import com.majstr.backend.service.ai.AiFlow;
import com.majstr.backend.service.ai.AiInput;
import com.majstr.backend.service.ai.JsonExtractor;
import com.majstr.backend.service.importer.EstimateExtractor.Extracted;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tests the JSON → {@link Extracted} mapping (the model's structured output),
 * without hitting the network. Locks the sentinel handling: an empty string reads as
 * null, a 0 deposit means "no deposit", and 0 quantity/price survive (flagged later on
 * the review screen). No Spring context — a plain {@link JsonMapper} is enough.
 */
class EstimateExtractorTest {

    /**
     * A stand-in for whichever provider is configured. These tests are about the JSON → Extracted
     * mapping, and after the split this class has no idea who produced that JSON — which is the
     * whole point: the estimate prompts now follow {@code app.ai.provider} like every other flow.
     */
    private static final JsonExtractor NO_PROVIDER = new JsonExtractor() {
        @Override
        public String requestJson(java.util.List<AiInput> input, String systemPrompt,
                                 java.util.Map<String, Object> schema) {
            throw new AiExtractionException("error.ai.unavailable");
        }

        @Override
        public String providerName() {
            return "test";
        }
    };

    private final EstimateExtractor extractor = new EstimateExtractor(
            new AiExtractors(new AiFlowsProperties(null, null, null),
                    new AnthropicProperties("", "m", 1), new OpenAiProperties("", "m", 1, null), NO_PROVIDER),
            JsonMapper.builder().build());

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

        Extracted result = extractor.parse(AiFlow.ESTIMATE, json);

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

        Extracted result = extractor.parse(AiFlow.ESTIMATE, json);

        assertThat(result.items()).hasSize(1); // the name-less row is skipped
        Extracted.Line line = result.items().get(0);
        assertThat(line.unit()).isNull();                    // "" → null
        assertThat(line.quantity()).isEqualByComparingTo("0"); // 0 kept → flagged on review
        assertThat(line.unitPrice()).isEqualByComparingTo("0");
        assertThat(result.depositAmount()).isNull();          // 0 deposit → absent
    }
}
