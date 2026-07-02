package com.majstr.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Billing / monobank acquiring configuration. The merchant token comes from the
 * environment only ({@code MONOBANK_TOKEN}); when blank (local dev without a
 * merchant account) the billing flow runs in a <b>dev-simulation</b> mode — a
 * checkout immediately grants PRO instead of calling monobank — so the whole
 * flow is buildable/testable without live credentials. Mirrors the env-gated,
 * fail-soft pattern of {@link EmailProperties} / {@code VapidProperties}.
 *
 * <p>{@code returnUrl} is where monobank redirects the payer back (the PWA);
 * {@code webhookUrl} is the public URL monobank calls to report the result
 * (must be reachable from the internet — prod, or a tunnel in dev).</p>
 *
 * <p>{@code allowDevSimulation} guards the free-PRO footgun: dev-simulation
 * (grant PRO with no token) is only permitted where this is true. It defaults
 * true (dev) and is forced <b>false in {@code application-prod.yml}</b> — so on
 * prod a missing token makes checkout fail loudly instead of handing out free PRO.</p>
 */
@ConfigurationProperties(prefix = "app.billing")
public record BillingProperties(
        String monobankToken,
        String monobankApiBase,
        BigDecimal proPrice,
        int proDays,
        int graceDays,
        String returnUrl,
        String webhookUrl,
        boolean allowDevSimulation
) {
    public boolean isConfigured() {
        return monobankToken != null && !monobankToken.isBlank();
    }
}
