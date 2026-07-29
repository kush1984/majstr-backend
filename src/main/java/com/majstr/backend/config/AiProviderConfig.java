package com.majstr.backend.config;

import com.majstr.backend.service.ai.JsonExtractor;
import com.majstr.backend.service.ai.OpenAiJsonExtractor;
import com.majstr.backend.service.importer.ClaudeEstimateExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Which provider reads a drawing — one switch, {@code app.ai.provider}.
 *
 * <p>Anthropic is the default and the shipped path; {@code openai} is there to compare the two on real
 * sheets before deciding anything. Selected at startup rather than per request on purpose: recognition
 * quality is only comparable if a whole run used one model, and a per-request flag would quietly mix
 * results from both.</p>
 *
 * <p>The chosen provider is logged at boot, because "which model produced this reading" is the first
 * question anyone asks about a bad result.</p>
 */
@Slf4j
@Configuration
public class AiProviderConfig {

    @Bean
    @Primary
    public JsonExtractor jsonExtractor(
            ClaudeEstimateExtractor anthropic,
            OpenAiProperties openAiProperties,
            @Value("${app.ai.provider:anthropic}") String provider) {

        if ("openai".equalsIgnoreCase(provider)) {
            if (!openAiProperties.isConfigured()) {
                // Fail loudly at boot rather than at the first import: a missing key here means every
                // recognition would report "unavailable", and the master would have no way to know why.
                throw new IllegalStateException(
                        "app.ai.provider=openai but OPENAI_API_KEY is not set");
            }
            log.info("AI recognition provider: OpenAI ({})", openAiProperties.model());
            return new OpenAiJsonExtractor(openAiProperties);
        }
        if (!"anthropic".equalsIgnoreCase(provider)) {
            throw new IllegalStateException(
                    "Unknown app.ai.provider '" + provider + "' — expected anthropic or openai");
        }
        log.info("AI recognition provider: {}", anthropic.providerName());
        return anthropic;
    }
}
