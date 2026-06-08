package com.majstr.backend.config;

import io.sentry.Sentry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Initializes the Sentry SDK once at startup, env-gated on {@code SENTRY_DSN}.
 *
 * <p>With a blank DSN (local dev) initialization is skipped, so {@code Sentry}
 * stays a no-op hub and the capture call in {@link com.majstr.backend.exception.GlobalExceptionHandler}
 * does nothing. With a DSN present, unhandled exceptions are reported with the
 * endpoint and an opaque user id but no PII (no email, body, headers or tokens).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SentryInitializer {

    private final SentryProperties properties;

    @PostConstruct
    void init() {
        if (!properties.isConfigured()) {
            log.info("Sentry disabled (no SENTRY_DSN configured)");
            return;
        }
        Sentry.init(options -> {
            options.setDsn(properties.dsn());
            options.setEnvironment(properties.environment());
            // Never collect PII automatically — we attach only a user id explicitly.
            options.setSendDefaultPii(false);
            if (properties.tracesSampleRate() != null) {
                options.setTracesSampleRate(properties.tracesSampleRate());
            }
        });
        log.info("Sentry initialized (environment={})", properties.environment());
    }
}
