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
 * Per-account cap on estimate-share emails (default 20/hour) — guards against
 * abusing the contractor's mailer to spam arbitrary addresses. Process-local
 * {@link ConcurrentHashMap}, same single-node limitation as the other limiters.
 */
@Component
public class EstimateEmailRateLimiter {

    private final ConcurrentMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();
    private final Bandwidth bandwidth;

    public EstimateEmailRateLimiter(RateLimitProperties props) {
        int maxPerHour = props.estimateEmail().maxPerHour();
        this.bandwidth = Bandwidth.builder()
                .capacity(maxPerHour)
                .refillIntervally(maxPerHour, Duration.ofHours(1))
                .build();
    }

    public ConsumeResult tryConsume(UUID accountId) {
        Bucket bucket = buckets.computeIfAbsent(accountId, k -> Bucket.builder().addLimit(bandwidth).build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new ConsumeResult(true, 0L);
        }
        long retryAfterSeconds = Math.max(1L, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        return new ConsumeResult(false, retryAfterSeconds);
    }

    public record ConsumeResult(boolean allowed, long retryAfterSeconds) {}
}
