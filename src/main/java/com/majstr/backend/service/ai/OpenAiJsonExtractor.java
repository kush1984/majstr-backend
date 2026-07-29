package com.majstr.backend.service.ai;

import com.majstr.backend.config.HttpClients;
import com.majstr.backend.config.OpenAiProperties;
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
 * The same recognition round-trip against OpenAI, for comparing the two on real sheets.
 *
 * <p>Uses the <strong>Responses</strong> API ({@code POST /v1/responses}), not Chat Completions: it is
 * the current surface and the one whose {@code text.format} carries strict structured outputs. Raw
 * HTTP rather than the SDK, matching how every other upstream in this codebase is called.</p>
 *
 * <p>The wire shapes here were taken from OpenAI's own documentation, not from memory — the input
 * parts are {@code input_file} / {@code input_image} / {@code input_text}, the system prompt is
 * {@code instructions}, the token cap is {@code max_output_tokens} (not {@code max_tokens}), and the
 * schema goes under {@code text.format}. Each of those differs from Anthropic's naming, which is the
 * whole reason {@link AiInput} exists.</p>
 *
 * <p>The prompts' JSON schemas need no change: OpenAI's strict mode requires
 * {@code additionalProperties: false} and every property listed in {@code required}, which is exactly
 * what {@code obj()} already produces for Anthropic.</p>
 */
@Slf4j
public class OpenAiJsonExtractor implements JsonExtractor {

    private static final String RESPONSES_URL = "https://api.openai.com/v1/responses";
    /** Names the schema in the request; OpenAI requires one and never shows it to the model. */
    private static final String SCHEMA_NAME = "majstr_extraction";

    private final OpenAiProperties props;
    private final RestClient restClient = HttpClients.forLlm();

    public OpenAiJsonExtractor(OpenAiProperties props) {
        this.props = props;
    }

    @Override
    public String providerName() {
        return "openai:" + props.model();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String requestJson(List<AiInput> input, String systemPrompt, Map<String, Object> schema) {
        if (!props.isConfigured()) {
            throw new AiExtractionException("error.ai.unavailable");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("max_output_tokens", props.maxTokens());
        body.put("instructions", systemPrompt);
        body.put("input", List.of(Map.of("role", "user", "content", parts(input))));
        body.put("text", Map.of("format", Map.of(
                "type", "json_schema",
                "name", SCHEMA_NAME,
                "strict", true,
                "schema", schema)));

        Map<String, Object> resp;
        try {
            resp = AiHttp.withRetry(providerName(), () -> restClient.post()
                    .uri(RESPONSES_URL)
                    .header("Authorization", "Bearer " + props.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class));
        } catch (Exception e) {
            log.error("OpenAI extraction call failed: {}", e.getMessage());
            throw new AiExtractionException("error.ai.unavailable", e);
        }
        return outputText(resp);
    }

    /** Our neutral input rendered into the Responses API's content parts. */
    static List<Map<String, Object>> parts(List<AiInput> input) {
        List<Map<String, Object>> parts = new ArrayList<>(input.size());
        for (AiInput in : input) {
            parts.add(switch (in) {
                case AiInput.Text t -> Map.of("type", "input_text", "text", t.text());
                // Both binary kinds go as data URIs — there is no separate upload step to keep the
                // "parsed and discarded, never stored" promise intact.
                case AiInput.Image i -> Map.of("type", "input_image",
                        "image_url", dataUri(i.mediaType(), i.bytes()));
                case AiInput.Pdf p -> Map.of("type", "input_file",
                        "filename", p.filename(),
                        "file_data", dataUri("application/pdf", p.bytes()));
            });
        }
        return parts;
    }

    private static String dataUri(String mediaType, byte[] bytes) {
        return "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * The model's text, scanned out of {@code output}.
     *
     * <p>Deliberately a scan rather than {@code output[0].content[0].text}: OpenAI's own docs say it
     * is not safe to assume the text sits at that position — reasoning models put other items in
     * {@code output} first, and indexing blindly would read the wrong one or throw.</p>
     */
    @SuppressWarnings("unchecked")
    String outputText(Map<String, Object> resp) {
        if (resp != null && resp.get("output") instanceof List<?> items) {
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> im) || !(im.get("content") instanceof List<?> blocks)) {
                    continue;
                }
                for (Object block : blocks) {
                    if (block instanceof Map<?, ?> bm && "output_text".equals(bm.get("type"))
                            && bm.get("text") instanceof String s && !s.isBlank()) {
                        return s;
                    }
                }
            }
        }
        // A cut-off answer arrives as a 200 with no usable text, so say which it was: "the model
        // ran out of room" and "the model said nothing" need different fixes.
        log.error("OpenAI returned no text (status={}, details={})",
                resp == null ? null : resp.get("status"),
                resp == null ? null : resp.get("incomplete_details"));
        throw new AiExtractionException("error.ai.unavailable");
    }
}
