package com.majstr.backend.service.importer;

import com.majstr.backend.exception.AiExtractionException;
import com.majstr.backend.service.ai.AiExtractors;
import com.majstr.backend.service.ai.AiFlow;
import com.majstr.backend.service.ai.AiInput;
import com.majstr.backend.service.ai.JsonExtractor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The parse tolerance the recognition leans on: the model's "unreadable" sentinels ("" / 0) come
 * back as nulls, a date is read in whatever shape the paper printed it, and only genuinely unusable
 * JSON throws (which the service then softens to recognized=false). Plus the round-2 fix: the
 * positions are read under the estimate import's receipt prompt, not a second one of our own.
 */
class ActReceiptExtractorTest {

    private final ActReceiptExtractor extractor =
            new ActReceiptExtractor(null, new ObjectMapper());

    @Test
    void sentinelsBecomeNulls_soTheDialogAsksInsteadOfInventing() {
        var read = extractor.parse("""
                {"label": "", "issuedAt": "", "total": 0}
                """);

        assertThat(read.label()).isNull();
        assertThat(read.issuedAt()).isNull();
        assertThat(read.total()).isNull();
        assertThat(read.items()).isEmpty();
    }

    @Test
    void aFullRead_carriesMetaAndItems() {
        var read = extractor.parse("""
                {"label": "Епіцентр", "issuedAt": "%s", "total": 483.50,
                 "items": [{"name": "Клей Ceresit CM-11", "unit": "шт", "quantity": 2,
                            "unitPrice": 241.75, "type": "MATERIAL", "category": ""}]}
                """.formatted(LocalDate.now().minusDays(2)));

        assertThat(read.label()).isEqualTo("Епіцентр");
        assertThat(read.issuedAt()).isEqualTo(LocalDate.now().minusDays(2));
        assertThat(read.total()).isEqualByComparingTo("483.50"); // kopecks survive
        assertThat(read.items()).singleElement()
                .satisfies(i -> assertThat(i.unitPrice()).isEqualByComparingTo("241.75"));
    }

    @Test
    void printedDateShapes_areReadAsPrinted_notOnlyIso() {
        // The prompt asks for ISO, but a model that echoes the paper's own «04.06.2026» read it
        // right — losing that on a format detail is our bug, not its (round-2 fix).
        assertThat(dateOf("04.06.2026")).isEqualTo(LocalDate.of(2026, 6, 4));
        assertThat(dateOf("04/06/2026")).isEqualTo(LocalDate.of(2026, 6, 4));
        assertThat(dateOf("04-06-26")).isEqualTo(LocalDate.of(2026, 6, 4)); // ККМ footer, 20YY
    }

    @Test
    void anOldReceiptKeepsItsDate_theMasterSeesItBeforeAdding() {
        // Paper a master photographs can be months or years old. The value only prefills a visible
        // field, so blanking it teaches "recognition doesn't take dates at all" for no gain.
        assertThat(dateOf("2009-06-04")).isEqualTo(LocalDate.of(2009, 6, 4));
    }

    @Test
    void aFutureDateOrGarbage_fallsBackToManualEntry() {
        assertThat(dateOf(LocalDate.now().plusDays(3).toString())).isNull(); // a mis-read year
        assertThat(dateOf("not a date")).isNull();
    }

    @Test
    void unusableJson_throws_forTheServiceToSoften() {
        assertThatThrownBy(() -> extractor.parse("not json at all"))
                .isInstanceOf(AiExtractionException.class);
    }

    private LocalDate dateOf(String raw) {
        return extractor.parse("{\"label\": \"X\", \"issuedAt\": \"" + raw + "\", \"total\": 100}")
                .issuedAt();
    }

    /** Which model reads what, and under which prompt — the half the master's report was about. */
    @Nested
    class Flows {

        private static final byte[] PHOTO = {1, 2, 3};

        private final AiExtractors extractors = mock(AiExtractors.class);
        private final JsonExtractor json = mock(JsonExtractor.class);
        private final ActReceiptExtractor full = new ActReceiptExtractor(extractors, new ObjectMapper());

        @SuppressWarnings("unchecked")
        private final ArgumentCaptor<List<AiInput>> input =
                ArgumentCaptor.forClass((Class<List<AiInput>>) (Class<?>) List.class);
        private final ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        private final ArgumentCaptor<Map<String, Object>> schema =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);

        private void modelAnswers(AiFlow flow, String body) {
            given(extractors.forFlow(flow)).willReturn(json);
            given(json.requestJson(anyList(), anyString(), anyMap())).willReturn(body);
        }

        @Test
        void theItemPass_runsOnTheReceiptFlow_underTheEstimateImportsOwnPrompt() {
            modelAnswers(AiFlow.RECEIPT, """
                    {"label": "Епіцентр", "issuedAt": "2026-08-20", "total": 482.75,
                     "depositAmount": 0,
                     "items": [{"name": "Труба каналізаційна ПП", "unit": "шт", "quantity": 8,
                                "unitPrice": 29.85, "type": "MATERIAL", "category": ""}]}
                    """);

            var read = full.extractWithItems("image/jpeg", PHOTO);

            verify(json).requestJson(input.capture(), prompt.capture(), schema.capture());
            // The tuned table instructions come from EstimateExtractor, verbatim — not a copy that
            // can drift; the act only appends the footer fields it needs on top.
            assertThat(prompt.getValue()).startsWith(EstimateExtractor.RECEIPT_SYSTEM_PROMPT);
            assertThat(prompt.getValue()).contains("issuedAt");
            assertThat(schema.getValue().get("required"))
                    .isEqualTo(List.of("items", "depositAmount", "label", "issuedAt", "total"));
            assertThat(read.items()).singleElement()
                    .satisfies(i -> assertThat(i.quantity()).isEqualByComparingTo("8"));
            assertThat(read.total()).isEqualByComparingTo("482.75");
            assertThat(read.issuedAt()).isEqualTo(LocalDate.of(2026, 8, 20));
        }

        @Test
        void theMetaPass_staysOnTheCheapActReceiptFlow() {
            modelAnswers(AiFlow.ACT_RECEIPT,
                    "{\"label\": \"Епіцентр\", \"issuedAt\": \"2026-08-20\", \"total\": 482.75}");

            var read = full.extractMeta("image/jpeg", PHOTO);

            verify(extractors).forFlow(AiFlow.ACT_RECEIPT);
            assertThat(read.items()).isEmpty();
            assertThat(read.total()).isEqualByComparingTo("482.75");
        }
    }
}
