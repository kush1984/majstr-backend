package com.majstr.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sentry error-monitoring configuration. The DSN comes from the environment only
 * ({@code SENTRY_DSN}); when it is blank (e.g. local dev) {@code SentryInitializer}
 * skips initialization entirely and every capture call becomes a no-op — the same
 * env-gated pattern as the email transport and web push.
 *
 * <p>{@code environment} tags events (dev / prod) so issues are separable per
 * deployment. {@code tracesSampleRate} is the fraction of transactions sampled
 * for performance tracing (0 = capture errors only).
 */
@ConfigurationProperties(prefix = "app.sentry")
public record SentryProperties(
        String dsn,
        String environment,
        Double tracesSampleRate
) {
    public boolean isConfigured() {
        return dsn != null && !dsn.isBlank();
    }
}
