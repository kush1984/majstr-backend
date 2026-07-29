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
 * Rate limit for messages arriving through a master's message link, keyed on IP + link token.
 *
 * <p>Separate from {@link PortalRateLimiter}, which guards every public read at 30/min: this endpoint
 * WRITES, and from the next step writes files, so it earns a limit of its own.</p>
 */
@Component
public class MessageLinkRateLimiter {

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Bandwidth bandwidth;

    public MessageLinkRateLimiter(RateLimitProperties props) {
        RateLimitProperties.MessageLink portal = props.messageLink();
        this.bandwidth = Bandwidth.builder()
                .capacity(portal.maxAttempts())
                .refillIntervally(portal.maxAttempts(), Duration.ofMinutes(portal.windowMinutes()))
                .build();
    }

    public ConsumeResult tryConsume(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> Bucket.builder().addLimit(bandwidth).build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new ConsumeResult(true, 0L);
        }
        long retryAfter = Math.max(1L, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        return new ConsumeResult(false, retryAfter);
    }

    public record ConsumeResult(boolean allowed, long retryAfterSeconds) {}
}
