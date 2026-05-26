package com.majstr.backend.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        Login login,
        Portal portal
) {
    public record Login(
            @Positive int maxAttempts,
            @Positive int windowMinutes
    ) {}

    public record Portal(
            @Positive int maxAttempts,
            @Positive int windowMinutes
    ) {}
}
