package com.majstr.backend.service.ai;

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
}
