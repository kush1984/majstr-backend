package com.majstr.backend.service.ai;

import com.majstr.backend.config.AnthropicProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Anthropic side of the same translation.
 *
 * <p>This used to be the only shape in the codebase and the call sites built it by hand, so nothing
 * checked it. Now that a second provider renders the same {@link AiInput} differently, both need
 * pinning — otherwise a refactor can quietly swap one vendor's field names into the other's request,
 * which fails only at real cost on a real sheet.</p>
 */
class AnthropicInputRenderingTest {

    private static final byte[] BYTES = "sheet".getBytes(StandardCharsets.UTF_8);
    private static final String B64 = "c2hlZXQ=";

    @Test
    void aPdfBecomesANativeDocumentBlock() {
        // `document` + source.media_type is Anthropic's shape; OpenAI's is input_file + file_data.
        // Native means Anthropic renders the pages itself — which is why the deploy needs no poppler.
        Map<String, Object> block = AnthropicJsonExtractor.blocks(AiInput.pdf(BYTES, "read it")).get(0);

        assertThat(block).containsEntry("type", "document");
        assertThat(block.get("source")).isEqualTo(Map.of(
                "type", "base64", "media_type", "application/pdf", "data", B64));
    }

    @Test
    void anImageCarriesItsMediaTypeAsItsOwnField() {
        Map<String, Object> block =
                AnthropicJsonExtractor.blocks(AiInput.image("image/webp", BYTES, "read it")).get(0);

        assertThat(block).containsEntry("type", "image");
        assertThat(block.get("source")).isEqualTo(Map.of(
                "type", "base64", "media_type", "image/webp", "data", B64));
    }

    @Test
    void theInstructionFollowsTheSheet() {
        List<Map<String, Object>> blocks = AnthropicJsonExtractor.blocks(AiInput.pdf(BYTES, "read it"));

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(1)).containsEntry("type", "text").containsEntry("text", "read it");
    }

    @Test
    void aTextGridRendersAsOneTextBlock() {
        List<Map<String, Object>> blocks = AnthropicJsonExtractor.blocks(AiInput.text("a grid"));

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).containsEntry("type", "text").containsEntry("text", "a grid");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSharedPromptIsSENTasACACHEABLEblock_notAsABareString() {
        // A bare `system: "..."` is valid and works — it just bills the full prompt every call. The
        // measurement prompt is ~7k tokens and a hard sheet is five calls that share it verbatim,
        // so the difference between the two shapes is roughly 5× the prompt versus 1.65×. Nothing
        // fails when this regresses; the bill just goes back up, which is why it is pinned here.
        Map<String, Object> body = extractor().buildBody(
                AiInput.pdf(BYTES, "read it"), "a very long shared prompt", Map.of("type", "object"));

        List<Map<String, Object>> system = (List<Map<String, Object>>) body.get("system");
        assertThat(system).hasSize(1);
        assertThat(system.get(0))
                .containsEntry("type", "text")
                .containsEntry("text", "a very long shared prompt")
                .containsEntry("cache_control", Map.of("type", "ephemeral"));
    }

    @Test
    void whatVARIESbetweenCallsStaysOUTofTheCachedPrefix() {
        // Caching is a prefix match: one differing byte before the breakpoint and the cache is
        // missed silently. The five calls on one sheet differ ONLY in the page image and the
        // fragment's position, and both of those belong in `messages`, after the system block.
        AnthropicJsonExtractor extractor = extractor();
        Object first = extractor.buildBody(
                AiInput.image("image/png", BYTES, "FRAGMENT 1 OF 4 — row 1 of 2, column 1 of 2"),
                "the shared prompt", Map.of()).get("system");
        Object second = extractor.buildBody(
                AiInput.image("image/png", "another fragment".getBytes(StandardCharsets.UTF_8),
                        "FRAGMENT 4 OF 4 — row 2 of 2, column 2 of 2"),
                "the shared prompt", Map.of()).get("system");

        assertThat(first).isEqualTo(second);
    }

    private static AnthropicJsonExtractor extractor() {
        return new AnthropicJsonExtractor(new AnthropicProperties("key", "claude-opus-5", 8000));
    }
}
