package com.majstr.backend.service;

import com.majstr.backend.config.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-user cooldown for resending the verification email (default 1 per 60s).
 * Process-local {@link ConcurrentHashMap} — same single-node limitation as the
 * other limiters (see open-questions).
 */
@Component
public class VerificationEmailRateLimiter {

    private final ConcurrentMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();
    private final Bandwidth bandwidth;

    public VerificationEmailRateLimiter(RateLimitProperties props) {
        int cooldown = props.verification().cooldownSeconds();
        this.bandwidth = Bandwidth.builder()
                .capacity(1)
                .refillIntervally(1, Duration.ofSeconds(cooldown))
                .build();
    }

    public ConsumeResult tryConsume(UUID userId) {
        Bucket bucket = buckets.computeIfAbsent(userId, k -> Bucket.builder().addLimit(bandwidth).build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new ConsumeResult(true, 0L);
        }
        long retryAfterSeconds = Math.max(1L, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        return new ConsumeResult(false, retryAfterSeconds);
    }

    public record ConsumeResult(boolean allowed, long retryAfterSeconds) {}
}
