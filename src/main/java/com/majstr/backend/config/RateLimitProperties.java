package com.majstr.backend.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        Login login,
        Register register,
        Forgot forgot,
        Portal portal,
        Verification verification,
        EstimateEmail estimateEmail,
        MessageLink messageLink
) {
    public record Login(
            @Positive int maxAttempts,
            @Positive int windowMinutes
    ) {}

    /** Cap on account registrations per client IP — curbs mass signups and verification-email spam. */
    public record Register(
            @Positive int maxAttempts,
            @Positive int windowMinutes
    ) {}

    /** Cap on password-reset requests per client IP+email — curbs reset-email spam. */
    public record Forgot(
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

    /**
     * Cap on messages sent through a master's message link, per IP AND per link.
     *
     * <p>Tighter than the blanket 30/min on /api/public/**, because this one WRITES — and, from the
     * next step, writes files. Keyed on the pair so one address cannot spray every link a master has,
     * and one leaked link cannot be filled from a hundred addresses either.</p>
     */
    public record MessageLink(
            @Positive int maxAttempts,
            @Positive int windowMinutes
    ) {}
}
