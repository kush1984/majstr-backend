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
        int maxTokens
) {
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
