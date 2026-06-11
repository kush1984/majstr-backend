package com.majstr.backend.service;

import com.majstr.backend.config.RateLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterRateLimiterTest {

    private RegisterRateLimiter limiter;

    @BeforeEach
    void setUp() {
        RateLimitProperties props = new RateLimitProperties(
                new RateLimitProperties.Login(5, 15),
                new RateLimitProperties.Register(3, 60),
                new RateLimitProperties.Portal(30, 1),
                new RateLimitProperties.Verification(60),
                new RateLimitProperties.EstimateEmail(20));
        limiter = new RegisterRateLimiter(props);
    }

    @Test
    void allowsUpToLimitThenBlocksWithRetryAfter() {
        String ip = "203.0.113.42";
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryConsume(ip).allowed()).as("attempt %d", i + 1).isTrue();
        }

        RegisterRateLimiter.ConsumeResult blocked = limiter.tryConsume(ip);

        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isPositive();
    }

    @Test
    void limitIsPerIp() {
        String first = "203.0.113.1";
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume(first);
        }
        assertThat(limiter.tryConsume(first).allowed()).isFalse();

        assertThat(limiter.tryConsume("203.0.113.2").allowed()).isTrue();
    }
}
