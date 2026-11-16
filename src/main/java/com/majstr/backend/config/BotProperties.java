package com.majstr.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * majster-bot — {@code app.bot.*}. The token and the webhook secret come from env only.
 *
 * <p>{@code mode}: {@code off} (no Telegram traffic at all — tests, and any environment without a
 * token), {@code polling} (local dev: no public URL needed) or {@code webhook} (prod).
 * {@code parsesPerDay} bounds the model calls per master across the bot AND the PWA quick add.</p>
 */
@ConfigurationProperties(prefix = "app.bot")
public record BotProperties(
        String telegramToken,
        String telegramUsername,
        String webhookSecret,
        String mode,
        int parsesPerDay,
        /** The PWA's own address — the bot links to «Вхідні» there. */
        String appUrl
) {
    public boolean enabled() {
        return telegramToken != null && !telegramToken.isBlank() && !"off".equalsIgnoreCase(mode);
    }

    public boolean polling() {
        return enabled() && "polling".equalsIgnoreCase(mode);
    }

    public boolean webhook() {
        return enabled() && "webhook".equalsIgnoreCase(mode);
    }
}
