package com.majstr.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The tax service's public receipt lookup, used by the fiscal-QR fast path.
 *
 * <p>{@code baseUrl} is configurable because the endpoint is <b>undocumented</b>: it was found by
 * unpacking the Electronic Cabinet's own bundle, and nothing promises it will keep its address or
 * its shape. A blank value disables the whole fast path — the same "an empty config value = absent"
 * rule the AI flows use — and every caller already treats a failed lookup as "not recognized", so
 * turning it off degrades to plain photo recognition instead of breaking a screen.</p>
 */
@ConfigurationProperties(prefix = "app.fiscal-qr")
public record FiscalQrProperties(String baseUrl) {

    public boolean enabled() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}
