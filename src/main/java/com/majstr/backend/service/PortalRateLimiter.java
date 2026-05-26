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
 * IP-keyed rate limiter for public portal endpoints; defends share-link
 * tokens against brute-force probing.
 */
@Component
public class PortalRateLimiter {

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Bandwidth bandwidth;

    public PortalRateLimiter(RateLimitProperties props) {
        RateLimitProperties.Portal portal = props.portal();
        this.bandwidth = Bandwidth.builder()
                .capacity(portal.maxAttempts())
                .refillIntervally(portal.maxAttempts(), Duration.ofMinutes(portal.windowMinutes()))
                .build();
    }

    public ConsumeResult tryConsume(String ip) {
        Bucket bucket = buckets.computeIfAbsent(ip, k -> Bucket.builder().addLimit(bandwidth).build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new ConsumeResult(true, 0L);
        }
        long retryAfter = Math.max(1L, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        return new ConsumeResult(false, retryAfter);
    }

    public record ConsumeResult(boolean allowed, long retryAfterSeconds) {}
}
