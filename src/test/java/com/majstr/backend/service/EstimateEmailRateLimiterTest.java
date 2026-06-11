package com.majstr.backend.service;

import com.majstr.backend.config.RateLimitProperties;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EstimateEmailRateLimiterTest {

    private EstimateEmailRateLimiter limiter(int maxPerHour) {
        RateLimitProperties props = new RateLimitProperties(
                new RateLimitProperties.Login(5, 15),
                new RateLimitProperties.Register(5, 60),
                new RateLimitProperties.Portal(30, 1),
                new RateLimitProperties.Verification(60),
                new RateLimitProperties.EstimateEmail(maxPerHour));
        return new EstimateEmailRateLimiter(props);
    }

    @Test
    void blocksAfterHourlyCap() {
        EstimateEmailRateLimiter limiter = limiter(2);
        UUID account = UUID.randomUUID();

        assertThat(limiter.tryConsume(account).allowed()).isTrue();
        assertThat(limiter.tryConsume(account).allowed()).isTrue();

        var third = limiter.tryConsume(account);
        assertThat(third.allowed()).isFalse();
        assertThat(third.retryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    void accountsAreIndependent() {
        EstimateEmailRateLimiter limiter = limiter(1);

        assertThat(limiter.tryConsume(UUID.randomUUID()).allowed()).isTrue();
        assertThat(limiter.tryConsume(UUID.randomUUID()).allowed()).isTrue();
    }
}
