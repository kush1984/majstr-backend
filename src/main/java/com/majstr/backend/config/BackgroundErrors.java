package com.majstr.backend.config;

import io.sentry.Sentry;
import io.sentry.SentryLevel;

/**
 * Reports a failure that happened OFF a request thread — an email, a push, an after-commit side
 * effect, a nightly job — to Sentry.
 *
 * <p>{@code GlobalExceptionHandler} reports only what escapes a controller, and every background
 * task here is fail-soft by design (it catches and logs, so a failed email never fails a
 * signature). Before this, those failures lived in the Railway log alone and Sentry looked
 * healthy while they happened. Each one is tagged {@code background=<task>} so they group by kind;
 * nothing else is attached — no recipient, no request — and {@code sendDefaultPii} stays off.</p>
 *
 * <p>A no-op when Sentry has no DSN (local dev), like every other capture.</p>
 */
public final class BackgroundErrors {

    private BackgroundErrors() {
    }

    public static void capture(String task, Throwable error) {
        capture(task, error, SentryLevel.ERROR);
    }

    public static void capture(String task, Throwable error, SentryLevel level) {
        Sentry.withScope(scope -> {
            scope.setTag("background", task);
            scope.setLevel(level);
            Sentry.captureException(error);
        });
    }
}
