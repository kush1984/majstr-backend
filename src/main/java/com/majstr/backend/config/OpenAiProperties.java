package com.majstr.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI configuration, for trying the same recognition against a second provider.
 *
 * <p>Blank key by default and inert unless {@code app.ai.provider} selects it, so adding this costs
 * nothing until someone deliberately switches over — the Anthropic path stays the shipped one.</p>
 */
@ConfigurationProperties(prefix = "app.openai")
public record OpenAiProperties(
        String apiKey,
        String model,
        /**
         * Reasoning tokens are billed as output tokens and count against THIS limit, so it is not
         * "how long may the answer be" — it is the whole thinking budget. OpenAI's own advice is to
         * reserve at least 25 000; at 8 000 a dense drawing can burn the budget mid-thought and
         * return an incomplete response you have already paid for.
         */
        int maxTokens,
        /** {@code none|minimal|low|medium|high|xhigh|max}; blank = do not send it, model default. */
        String reasoningEffort
) {
    /**
     * The same credentials pointed at a different model — how one vendor serves several flows
     * (a cheap model for receipts, the strongest one for drawings) without a second API key.
     */
    public OpenAiProperties withModel(String override) {
        return override == null || override.isBlank() ? this
                : new OpenAiProperties(apiKey, override.trim(), maxTokens, reasoningEffort);
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
