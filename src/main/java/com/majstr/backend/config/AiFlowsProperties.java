package com.majstr.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Which model reads what — {@code app.ai.*}.
 *
 * <p>Everything here is optional, and with nothing set the behaviour is exactly what it was: the
 * vendor from {@code app.ai.provider} on that vendor's own configured model, for every flow.</p>
 *
 * <pre>
 * app.ai.provider = anthropic            # the vendor, unless a flow overrides it
 * app.ai.model    = claude-opus-4-8      # optional: override that vendor's default model
 * app.ai.flows.receipt = anthropic:claude-sonnet-5
 * app.ai.flows.electrical = openai:gpt-5.6
 * </pre>
 *
 * <p>A flow's value is {@code vendor:model}, or just a model (the default vendor keeps it company),
 * or just a vendor (its own default model). One string carries both because a model name alone is
 * ambiguous across vendors, and because "which model produced this reading" has to be ONE token in
 * a log line to be usable.</p>
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiFlowsProperties(
        String provider,
        String model,
        /** Flow key ({@link com.majstr.backend.service.ai.AiFlow#key()}) → spec. */
        Map<String, String> flows
) {
    public String specFor(String flowKey) {
        if (flows == null) {
            return null;
        }
        String spec = flows.get(flowKey);
        // A key that exists with an empty value is the same as no key — the shape a
        // `${AI_FLOW_RECEIPT:}` placeholder takes when nobody sets the variable, and the exact trap
        // that once turned an unset provider into 25 failed integration tests.
        return spec == null || spec.isBlank() ? null : spec.trim();
    }
}
