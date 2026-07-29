package com.majstr.backend.service.ai;

import com.majstr.backend.config.OpenAiProperties;
import com.majstr.backend.exception.AiExtractionException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The OpenAI translation layer.
 *
 * <p>Only two things here can realistically be wrong, and neither shows up until a real sheet is run
 * at real cost: the field names — OpenAI's are different from Anthropic's at every position, and a
 * wrong one is a 400 after the file is uploaded — and reading the answer back out, where OpenAI's own
 * documentation warns that the text is not necessarily the first thing in {@code output}.</p>
 */
class OpenAiJsonExtractorTest {

    private static final byte[] BYTES = "sheet".getBytes(StandardCharsets.UTF_8);
    /** Base64 of "sheet" — pinned so a change in how we encode is visible, not inferred. */
    private static final String B64 = "c2hlZXQ=";

    private static OpenAiJsonExtractor extractor(String key) {
        return new OpenAiJsonExtractor(new OpenAiProperties(key, "gpt-5.6", 8000));
    }

    // ---- input rendering --------------------------------------------------------

    @Test
    void aPdfBecomesAnInputFilePartWithADataUri() {
        // input_file / filename / file_data are OpenAI's names. Anthropic's document block uses
        // source.media_type + source.data instead — same bytes, nothing else shared.
        Map<String, Object> part = OpenAiJsonExtractor.parts(AiInput.pdf(BYTES, "read it")).get(0);

        assertThat(part).containsEntry("type", "input_file");
        assertThat(part).containsEntry("filename", "document.pdf");
        assertThat(part).containsEntry("file_data", "data:application/pdf;base64," + B64);
    }

    @Test
    void anImageBecomesAnInputImagePartWithItsOwnMediaTypeInTheUri() {
        // The media type rides inside the data URI here, not in a separate field — get it wrong and
        // the model is handed a PNG labelled as a JPEG.
        Map<String, Object> part =
                OpenAiJsonExtractor.parts(AiInput.image("image/png", BYTES, "read it")).get(0);

        assertThat(part).containsEntry("type", "input_image");
        assertThat(part).containsEntry("image_url", "data:image/png;base64," + B64);
    }

    @Test
    void theInstructionKeepsItsPlaceAfterTheFile() {
        // Order is meaningful to both providers: the sheet first, then what to do with it.
        List<Map<String, Object>> parts = OpenAiJsonExtractor.parts(AiInput.pdf(BYTES, "read it"));

        assertThat(parts).hasSize(2);
        assertThat(parts.get(1)).containsEntry("type", "input_text").containsEntry("text", "read it");
    }

    @Test
    void aTextOnlyInputRendersAsOneTextPart() {
        List<Map<String, Object>> parts = OpenAiJsonExtractor.parts(AiInput.text("a grid"));

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0)).containsEntry("type", "input_text").containsEntry("text", "a grid");
    }

    // ---- reading the answer back -----------------------------------------------

    @Test
    void findsTheTextEvenWhenItIsNotTheFirstItemInOutput() {
        // The case OpenAI's docs warn about: a reasoning model puts other items in `output` first.
        // Indexing output[0].content[0] would read the wrong one or throw.
        Map<String, Object> resp = Map.of("output", List.of(
                Map.of("type", "reasoning", "summary", List.of()),
                Map.of("type", "message", "content", List.of(
                        Map.of("type", "output_text", "text", "{\"floors\":[]}")))));

        assertThat(extractor("sk-x").outputText(resp)).isEqualTo("{\"floors\":[]}");
    }

    @Test
    void aResponseWithNoTextIsAnExtractionFailure() {
        // A cut-off answer arrives as a 200 with nothing usable — it must not be handed on as if the
        // model had said something.
        Map<String, Object> resp = Map.of(
                "status", "incomplete",
                "incomplete_details", Map.of("reason", "max_output_tokens"),
                "output", List.of());

        assertThatThrownBy(() -> extractor("sk-x").outputText(resp))
                .isInstanceOf(AiExtractionException.class)
                .hasMessage("error.ai.unavailable");
    }

    @Test
    void anEmptyTextBlockDoesNotCountAsAnAnswer() {
        Map<String, Object> resp = Map.of("output", List.of(
                Map.of("type", "message", "content", List.of(
                        Map.of("type", "output_text", "text", "   ")))));

        assertThatThrownBy(() -> extractor("sk-x").outputText(resp))
                .isInstanceOf(AiExtractionException.class);
    }

    // ---- configuration ---------------------------------------------------------

    @Test
    void anUnconfiguredKeyFailsBeforeAnythingIsSent() {
        assertThatThrownBy(() -> extractor("")
                .requestJson(AiInput.text("x"), "system", Map.of("type", "object")))
                .isInstanceOf(AiExtractionException.class)
                .hasMessage("error.ai.unavailable");
    }

    @Test
    void theProviderNameSaysWhichModelDidTheReading() {
        // Logged on every run: "which model produced this reading" is the first question about a bad
        // result, and with two providers in play it stops being self-evident.
        assertThat(extractor("sk-x").providerName()).isEqualTo("openai:gpt-5.6");
    }
}
