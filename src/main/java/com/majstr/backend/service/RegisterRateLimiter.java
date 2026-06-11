package com.majstr.backend.service;

import com.majstr.backend.config.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Caps registrations per client IP. Unlike login (keyed by email+IP), there is
 * no account yet, so the IP is the only stable key. Same single-node in-memory
 * limitation as the other limiters.
 */
@Component
public class RegisterRateLimiter {

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Bandwidth bandwidth;

    public RegisterRateLimiter(RateLimitProperties props) {
        RateLimitProperties.Register register = props.register();
        this.bandwidth = Bandwidth.builder()
                .capacity(register.maxAttempts())
                .refillIntervally(register.maxAttempts(), Duration.ofMinutes(register.windowMinutes()))
                .build();
    }

    public ConsumeResult tryConsume(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> Bucket.builder().addLimit(bandwidth).build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new ConsumeResult(true, 0L);
        }
        long retryAfterSeconds = Math.max(1L, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        return new ConsumeResult(false, retryAfterSeconds);
    }

    public record ConsumeResult(boolean allowed, long retryAfterSeconds) {}
}
