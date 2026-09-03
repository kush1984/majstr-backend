package com.majstr.backend.service.importer;

import com.majstr.backend.exception.AiExtractionException;
import com.majstr.backend.service.ai.AiExtractors;
import com.majstr.backend.service.ai.AiFlow;
import com.majstr.backend.service.ai.AiInput;
import com.majstr.backend.service.ai.JsonExtractor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The parse tolerance the dictation review leans on: the prompt's "not said" sentinels ("" / 0)
 * come back as nulls so the master is asked instead of a number being invented, and only genuinely
 * unusable JSON throws.
 */
class DictationExtractorTest {

    private final DictationExtractor extractor = new DictationExtractor(null, new ObjectMapper());

    @Test
    void sentinelsBecomeNulls_soTheReviewAsksInsteadOfInventing() {
        var lines = extractor.parse("""
                {"items": [{"name": "поклеїти шпалери", "unit": "", "quantity": 0,
                            "unitPrice": 0, "type": ""}]}
                """);

        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.name()).isEqualTo("поклеїти шпалери");
            assertThat(line.unit()).isNull();
            assertThat(line.quantity()).isNull();
            assertThat(line.unitPrice()).isNull(); // the catalog fills this in, not the model
            assertThat(line.type()).isNull();
        });
    }

    @Test
    void aFullySpokenLine_carriesItsNumbers() {
        var lines = extractor.parse("""
                {"items": [{"name": "штукатурка стін", "unit": "м2", "quantity": 25.5,
                            "unitPrice": 320, "type": "WORK"}]}
                """);

        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualByComparingTo("25.5");
            assertThat(line.unitPrice()).isEqualByComparingTo("320");
        });
    }

    @Test
    void aRowWithNoName_isNotAPosition() {
        var lines = extractor.parse("""
                {"items": [{"name": "", "unit": "м2", "quantity": 10, "unitPrice": 0, "type": "WORK"},
                           {"name": "фарбування стелі", "unit": "м2", "quantity": 10,
                            "unitPrice": 0, "type": "WORK"}]}
                """);

        assertThat(lines).extracting(DictationExtractor.Spoken::name)
                .containsExactly("фарбування стелі");
    }

    @Test
    void textWithNoPositionsAtAll_isAnEmptyList_notAnError() {
        assertThat(extractor.parse("{\"items\": []}")).isEmpty();
        assertThat(extractor.parse("{}")).isEmpty();
    }

    @Test
    void unusableJson_throws() {
        assertThatThrownBy(() -> extractor.parse("not json at all"))
                .isInstanceOf(AiExtractionException.class);
    }

    @Test
    void theTextRidesItsOwnFlow_andReachesTheModelAsText() {
        // No image, no file — the one extractor here whose whole input is a sentence.
        AiExtractors extractors = mock(AiExtractors.class);
        JsonExtractor json = mock(JsonExtractor.class);
        given(extractors.forFlow(AiFlow.DICTATION)).willReturn(json);
        given(json.requestJson(anyList(), anyString(), anyMap())).willReturn("{\"items\": []}");

        new DictationExtractor(extractors, new ObjectMapper()).extract("поклеїти шпалери 20 квадратів");

        verify(extractors).forFlow(AiFlow.DICTATION);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiInput>> input = ArgumentCaptor.forClass(List.class);
        verify(json).requestJson(input.capture(), anyString(), anyMap());
        assertThat(input.getValue()).singleElement()
                .isEqualTo(new AiInput.Text("поклеїти шпалери 20 квадратів"));
    }
}
