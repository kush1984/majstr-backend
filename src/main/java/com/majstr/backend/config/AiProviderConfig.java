package com.majstr.backend.config;

import com.majstr.backend.service.ai.AnthropicJsonExtractor;
import com.majstr.backend.service.ai.JsonExtractor;
import com.majstr.backend.service.ai.MisconfiguredJsonExtractor;
import com.majstr.backend.service.ai.OpenAiJsonExtractor;
import lombok.extern.slf4j.Slf4j;
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
 *
 * <p><b>A misconfiguration here never stops the application.</b> An earlier version threw, which
 * meant a missing recognition key took down estimates, PDFs, the portal and billing — none of which
 * use it — and, twice, an entire CI suite. A bad setting now disables recognition alone and says so
 * in the log; see {@link MisconfiguredJsonExtractor} for why that beats both crashing and silently
 * substituting another provider.</p>
 */
@Slf4j
@Configuration
public class AiProviderConfig {

    private static final String ANTHROPIC = "anthropic";
    private static final String OPENAI = "openai";

    @Bean
    @Primary
    public JsonExtractor jsonExtractor(
            AiFlowsProperties ai,
            AnthropicProperties anthropicProperties,
            OpenAiProperties openAiProperties) {
        String provider = ai.provider();

        // "Set to nothing" has to mean "not set". The `:anthropic` default only fires when the key is
        // ABSENT, and an environment variable that exists but is empty — `AI_PROVIDER=` in a .env, a CI
        // variable expanded from an undefined secret — is present with an empty value. Left unhandled
        // that empty string falls through to the unknown-provider check below and fails the context,
        // which is how an unconfigured CI runner came to fail every integration test in the suite.
        String chosen = provider == null || provider.isBlank() ? ANTHROPIC : provider.trim();
        // `app.ai.model` re-points the chosen vendor at a different model of its own — the
        // one-line version of the per-flow overrides, for when every flow should move together.
        AnthropicProperties anthropicWithModel = anthropicProperties.withModel(ai.model());
        OpenAiProperties openAiWithModel = openAiProperties.withModel(ai.model());

        if (OPENAI.equalsIgnoreCase(chosen)) {
            if (!openAiProperties.isConfigured()) {
                return new MisconfiguredJsonExtractor(
                        "app.ai.provider=openai but OPENAI_API_KEY is not set");
            }
            log.info("AI recognition provider: OpenAI ({})", openAiWithModel.model());
            return new OpenAiJsonExtractor(openAiWithModel);
        }
        // A typo must not quietly become Anthropic: a comparison run attributed to the wrong model
        // is the one failure that makes the numbers lie. Recognition stops, the app does not.
        if (!ANTHROPIC.equalsIgnoreCase(chosen)) {
            return new MisconfiguredJsonExtractor(
                    "unknown app.ai.provider '" + chosen + "' — expected anthropic or openai");
        }
        if (!anthropicProperties.isConfigured()) {
            // Blank in local dev is normal and always was: the app runs, recognition reports
            // "unavailable". Saying so once at boot beats discovering it per import.
            return new MisconfiguredJsonExtractor("ANTHROPIC_API_KEY is not set");
        }
        JsonExtractor anthropic = new AnthropicJsonExtractor(anthropicWithModel);
        log.info("AI recognition provider: {}", anthropic.providerName());
        return anthropic;
    }
}
