package com.majstr.backend.service.ai;

import com.majstr.backend.config.AnthropicProperties;
import com.majstr.backend.config.HttpClients;
import com.majstr.backend.exception.AiExtractionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Anthropic round-trip and nothing else: send inputs under a prompt, come back with JSON.
 *
 * <p>Split out of {@code ClaudeEstimateExtractor}, which used to be both this transport AND the
 * owner of the estimate/receipt prompts. That pairing is why those two flows could not follow
 * {@code app.ai.provider}: anything that wanted the estimate prompts got Anthropic bundled with
 * them, and the class could hardly inject itself. Now the transport is one thing, domain knowledge
 * is another, and every flow reaches its provider through {@link JsonExtractor}.</p>
 *
 * <p>Raw HTTP ({@link RestClient}) matching the codebase's no-SDK precedent. Structured output is
 * enforced with {@code output_config.format}, so the response's first text block is the parseable
 * result — no beta headers needed, vision and structured outputs are GA on the Opus tier.</p>
 */
@Slf4j
public class AnthropicJsonExtractor implements JsonExtractor {

    private static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final AnthropicProperties props;
    /**
     * Explicit timeouts, shared by every flow that goes through here (estimate, receipt, sketch,
     * electrical plan, project import ×5 passes) — without a read timeout one stalled response
     * would hold a Tomcat thread until the pool is gone.
     */
    private final RestClient restClient = HttpClients.forLlm();

    public AnthropicJsonExtractor(AnthropicProperties props) {
        this.props = props;
    }

    @Override
    public String providerName() {
        return "anthropic:" + props.model();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String requestJson(List<AiInput> content, String systemPrompt,
                              Map<String, Object> schema) {
        if (!props.isConfigured()) {
            throw new AiExtractionException("error.ai.unavailable");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("max_tokens", props.maxTokens());
        body.put("system", systemPrompt);
        body.put("messages", List.of(Map.of("role", "user", "content", blocks(content))));
        body.put("output_config", Map.of("format",
                Map.of("type", "json_schema", "schema", schema)));

        Map<String, Object> resp;
        try {
            // The retry policy lives in AiHttp so both providers share one definition; this class
            // used to carry a second copy of it, which is exactly how two policies drift apart.
            resp = AiHttp.withRetry(providerName(), () -> restClient.post()
                    .uri(MESSAGES_URL)
                    .header("x-api-key", props.apiKey())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class));
        } catch (Exception e) {
            log.error("Anthropic extraction call failed: {}", e.getMessage());
            throw new AiExtractionException("error.ai.unavailable", e);
        }
        return firstTextBlock(resp);
    }

    /**
     * Our neutral input rendered into Anthropic's content blocks. A PDF goes as a native
     * {@code document} block — Anthropic renders the pages itself, which is why the deploy needs no
     * poppler for an architect's plan.
     */
    static List<Map<String, Object>> blocks(List<AiInput> input) {
        List<Map<String, Object>> out = new ArrayList<>(input.size());
        for (AiInput in : input) {
            out.add(switch (in) {
                case AiInput.Text t -> Map.<String, Object>of("type", "text", "text", t.text());
                case AiInput.Image i -> Map.<String, Object>of("type", "image", "source",
                        Map.of("type", "base64", "media_type", i.mediaType(),
                                "data", base64(i.bytes())));
                case AiInput.Pdf pdf -> Map.<String, Object>of("type", "document", "source",
                        Map.of("type", "base64", "media_type", "application/pdf",
                                "data", base64(pdf.bytes())));
            });
        }
        return out;
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Anthropic puts the answer in the first text block. Unlike OpenAI's {@code output} array this
     * position is stable, but a 200 with no text block at all still has to fail loudly rather than
     * hand an empty string to a parser.
     */
    private String firstTextBlock(Map<String, Object> resp) {
        Object content = resp == null ? null : resp.get("content");
        if (content instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> map && "text".equals(map.get("type"))) {
                    Object text = map.get("text");
                    if (text instanceof String s && !s.isBlank()) {
                        return s;
                    }
                }
            }
        }
        log.error("Anthropic returned no text block (stop_reason={})",
                resp == null ? null : resp.get("stop_reason"));
        throw new AiExtractionException("error.ai.unavailable");
    }
}
