package com.majstr.backend.service;

import com.majstr.backend.config.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Per-IP bucket for the public calculators — a stranger picks the key, hence {@link BucketRegistry} (B-22). */
@Component
public class PublicCalcRateLimiter {

    private final BucketRegistry<String> buckets;

    public PublicCalcRateLimiter(RateLimitProperties props) {
        int maxPerMinute = props.publicCalc().maxPerMinute();
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(maxPerMinute)
                .refillIntervally(maxPerMinute, Duration.ofMinutes(1))
                .build();
        this.buckets = new BucketRegistry<>(bandwidth, Duration.ofMinutes(1));
    }

    public PortalRateLimiter.ConsumeResult tryConsume(String ip) {
        Bucket bucket = buckets.get(ip);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new PortalRateLimiter.ConsumeResult(true, 0L);
        }
        long retryAfter = Math.max(1L, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        return new PortalRateLimiter.ConsumeResult(false, retryAfter);
    }
}
