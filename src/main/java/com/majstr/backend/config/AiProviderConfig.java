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

    private static final String ANTHROPIC = "anthropic";
    private static final String OPENAI = "openai";

    @Bean
    @Primary
    public JsonExtractor jsonExtractor(
            ClaudeEstimateExtractor anthropic,
            OpenAiProperties openAiProperties,
            @Value("${app.ai.provider:anthropic}") String provider) {

        // "Set to nothing" has to mean "not set". The `:anthropic` default only fires when the key is
        // ABSENT, and an environment variable that exists but is empty — `AI_PROVIDER=` in a .env, a CI
        // variable expanded from an undefined secret — is present with an empty value. Left unhandled
        // that empty string falls through to the unknown-provider check below and fails the context,
        // which is how an unconfigured CI runner came to fail every integration test in the suite.
        String chosen = provider == null || provider.isBlank() ? ANTHROPIC : provider.trim();

        if (OPENAI.equalsIgnoreCase(chosen)) {
            if (!openAiProperties.isConfigured()) {
                // Fail loudly at boot rather than at the first import: a missing key here means every
                // recognition would report "unavailable", and the master would have no way to know why.
                throw new IllegalStateException(
                        "app.ai.provider=openai but OPENAI_API_KEY is not set");
            }
            log.info("AI recognition provider: OpenAI ({})", openAiProperties.model());
            return new OpenAiJsonExtractor(openAiProperties);
        }
        // A typo must not fall back to Anthropic: a comparison run silently attributed to the wrong
        // model is worse than a boot failure. The value is quoted because it is the whole diagnosis.
        if (!ANTHROPIC.equalsIgnoreCase(chosen)) {
            throw new IllegalStateException(
                    "Unknown app.ai.provider '" + chosen + "' — expected anthropic or openai");
        }
        log.info("AI recognition provider: {}", anthropic.providerName());
        return anthropic;
    }
}
