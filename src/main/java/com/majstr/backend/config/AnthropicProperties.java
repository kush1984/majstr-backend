package com.majstr.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Anthropic (Claude) API configuration for the estimate-import LLM extraction.
 * The API key comes from the environment only ({@code ANTHROPIC_API_KEY}); when
 * blank (e.g. local dev without a key) the import feature reports "unavailable"
 * rather than failing with a 500 — unlike the fire-and-forget email/push
 * integrations, this call is synchronous and the master is waiting on its result.
 */
@ConfigurationProperties(prefix = "app.anthropic")
public record AnthropicProperties(
        String apiKey,
        String model,
        int maxTokens
) {
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
