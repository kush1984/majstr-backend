package com.majstr.backend.service.ai;

import java.util.List;
import java.util.Map;

/**
 * One vision round-trip that must come back as JSON matching {@code schema}.
 *
 * <p>Every recognition flow in the app — estimate, receipt, sketch, electrical plan, project
 * documentation — goes through this one method, which is why swapping the provider is a
 * configuration change rather than a rewrite.</p>
 *
 * <p>The schema is the same object for both providers on purpose: Anthropic's structured outputs and
 * OpenAI's strict mode impose the same two rules — every object needs {@code additionalProperties:
 * false} and every property must be listed in {@code required}. The prompts' schemas already satisfy
 * both, so no per-provider variant is needed.</p>
 */
public interface JsonExtractor {

    /**
     * @return the model's JSON as a string; never blank
     * @throws com.majstr.backend.exception.AiExtractionException on anything that stops us getting
     *         one — an unconfigured key, an HTTP failure, or a response with no text in it. The
     *         import is synchronous, so the master is told rather than the failure being swallowed.
     */
    String requestJson(List<AiInput> input, String systemPrompt, Map<String, Object> schema);

    /** Which provider this is, for logs and for the admin screen to show what did the reading. */
    String providerName();
}
