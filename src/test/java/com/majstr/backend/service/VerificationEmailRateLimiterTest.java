package com.majstr.backend.service;

import com.majstr.backend.config.RateLimitProperties;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationEmailRateLimiterTest {

    private VerificationEmailRateLimiter limiter() {
        RateLimitProperties props = new RateLimitProperties(
                new RateLimitProperties.Login(5, 15),
                new RateLimitProperties.Portal(30, 1),
                new RateLimitProperties.Verification(60),
                new RateLimitProperties.EstimateEmail(20));
        return new VerificationEmailRateLimiter(props);
    }

    @Test
    void secondResendWithinCooldownIsBlocked() {
        VerificationEmailRateLimiter limiter = limiter();
        UUID user = UUID.randomUUID();

        assertThat(limiter.tryConsume(user).allowed()).isTrue();

        var second = limiter.tryConsume(user);
        assertThat(second.allowed()).isFalse();
        assertThat(second.retryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    void differentUsersHaveIndependentCooldowns() {
        VerificationEmailRateLimiter limiter = limiter();

        assertThat(limiter.tryConsume(UUID.randomUUID()).allowed()).isTrue();
        assertThat(limiter.tryConsume(UUID.randomUUID()).allowed()).isTrue();
    }
}
