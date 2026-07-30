package com.majstr.backend.service.ai;

import com.majstr.backend.config.AiFlowsProperties;
import com.majstr.backend.config.AnthropicProperties;
import com.majstr.backend.config.OpenAiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Which extractor answers for which job.
 *
 * <p>One vendor and one model for everything was the simple version, and it is the wrong shape for
 * what these jobs actually are: a receipt is a small printed table read many times a day, an A3
 * measure plan is dense line-work read in five passes. The same model cannot be the right price for
 * both. A flow may now name its own {@code vendor:model}, and adding a third vendor is one more
 * implementation of {@link JsonExtractor} plus one branch in {@link #build} — no service changes.</p>
 *
 * <p><b>Nothing set changes nothing.</b> Every flow falls back to the default extractor, which is
 * exactly what the whole application used before this existed.</p>
 *
 * <p>Resolution happens ONCE, at startup, and the whole mapping is logged. Two reasons: recognition
 * quality is only comparable if a run used one model per flow rather than whatever a request
 * happened to pick, and "which model produced this reading" is the first question about a bad
 * result — it must be answerable from the log, not by re-reading config.</p>
 */
@Slf4j
@Component
public class AiExtractors {

    private final Map<AiFlow, JsonExtractor> byFlow = new EnumMap<>(AiFlow.class);

    public AiExtractors(AiFlowsProperties props,
                        AnthropicProperties anthropic,
                        OpenAiProperties openAi,
                        JsonExtractor defaultExtractor) {
        // One instance per distinct spec: two flows on the same vendor+model share a client rather
        // than opening a second one for the same destination.
        Map<String, JsonExtractor> cache = new HashMap<>();
        for (AiFlow flow : AiFlow.values()) {
            String spec = props.specFor(flow.key());
            JsonExtractor extractor = spec == null
                    ? defaultExtractor
                    : cache.computeIfAbsent(spec.toLowerCase(Locale.ROOT),
                            key -> build(key, props, anthropic, openAi));
            byFlow.put(flow, extractor);
        }
        byFlow.forEach((flow, extractor) ->
                log.info("AI flow {} → {}", flow.key(), extractor.providerName()));
    }

    /** The extractor for one job. Resolved at startup; this is a lookup, not a decision. */
    public JsonExtractor forFlow(AiFlow flow) {
        JsonExtractor extractor = byFlow.get(flow);
        if (extractor == null) {
            // Unreachable while every enum constant is resolved in the constructor — but a new flow
            // added without a mapping must not NPE its way into a 500.
            return new MisconfiguredJsonExtractor("no extractor resolved for flow " + flow.key());
        }
        return extractor;
    }

    /**
     * {@code vendor:model} → a client. A spec may also be just a model (the default vendor keeps
     * it) or just a vendor (its own configured model), because both are things a person actually
     * writes.
     */
    private JsonExtractor build(String spec, AiFlowsProperties props,
                                AnthropicProperties anthropic, OpenAiProperties openAi) {
        int colon = spec.indexOf(':');
        String vendor = colon >= 0 ? spec.substring(0, colon).trim() : null;
        String model = colon >= 0 ? spec.substring(colon + 1).trim() : spec.trim();
        if (vendor == null) {
            // No vendor named: is this a bare vendor, or a model for the default vendor?
            if (isVendor(model)) {
                vendor = model;
                model = null;
            } else {
                vendor = defaultVendor(props);
            }
        }
        return switch (vendor.toLowerCase(Locale.ROOT)) {
            case "anthropic" -> anthropic.isConfigured()
                    ? new AnthropicJsonExtractor(anthropic.withModel(model))
                    : new MisconfiguredJsonExtractor(
                            "flow model '" + spec + "' needs ANTHROPIC_API_KEY, which is not set");
            case "openai" -> openAi.isConfigured()
                    ? new OpenAiJsonExtractor(openAi.withModel(model))
                    : new MisconfiguredJsonExtractor(
                            "flow model '" + spec + "' needs OPENAI_API_KEY, which is not set");
            // A typo disables that ONE flow and says so. It must not quietly become the default:
            // results attributed to a model nobody chose are worse than results that never came.
            default -> new MisconfiguredJsonExtractor(
                    "unknown vendor in '" + spec + "' — expected anthropic or openai");
        };
    }

    private static boolean isVendor(String value) {
        return "anthropic".equalsIgnoreCase(value) || "openai".equalsIgnoreCase(value);
    }

    private static String defaultVendor(AiFlowsProperties props) {
        String provider = props.provider();
        return provider == null || provider.isBlank() ? "anthropic" : provider.trim();
    }
}
