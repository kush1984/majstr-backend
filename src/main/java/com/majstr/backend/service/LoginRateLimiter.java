package com.majstr.backend.service;

import com.majstr.backend.config.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class LoginRateLimiter {

    private final BucketRegistry<String> buckets;
    private final Bandwidth bandwidth;

    public LoginRateLimiter(RateLimitProperties props) {
        RateLimitProperties.Login login = props.login();
        this.bandwidth = Bandwidth.builder()
                .capacity(login.maxAttempts())
                .refillIntervally(login.maxAttempts(), Duration.ofMinutes(login.windowMinutes()))
                .build();
        // The map that used to sit here never dropped a key (B-22).
        this.buckets = new BucketRegistry<>(this.bandwidth, Duration.ofMinutes(login.windowMinutes()));
    }

    public ConsumeResult tryConsume(String key) {
        Bucket bucket = buckets.get(key);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new ConsumeResult(true, 0L);
        }
        long retryAfterSeconds = Math.max(1L, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        return new ConsumeResult(false, retryAfterSeconds);
    }

    public record ConsumeResult(boolean allowed, long retryAfterSeconds) {}
}
