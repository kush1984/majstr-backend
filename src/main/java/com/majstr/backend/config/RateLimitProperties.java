package com.majstr.backend.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        Login login,
        Portal portal,
        Verification verification,
        EstimateEmail estimateEmail
) {
    public record Login(
            @Positive int maxAttempts,
            @Positive int windowMinutes
    ) {}

    public record Portal(
            @Positive int maxAttempts,
            @Positive int windowMinutes
    ) {}

    /** Cooldown between verification-email resends, per user. */
    public record Verification(
            @Positive int cooldownSeconds
    ) {}

    /** Cap on estimate-share emails per account per hour. */
    public record EstimateEmail(
            @Positive int maxPerHour
    ) {}
}
