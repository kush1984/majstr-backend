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
 * Caps password-reset requests per client IP+email (default 5/hour) — curbs abusing the
 * mailer to spam reset links at an address. Process-local {@link ConcurrentHashMap}, same
 * single-node limitation as the other limiters. Keyed on IP+email (not the account, which
 * may not exist — the endpoint is anti-enumeration and always answers 200).
 */
@Component
public class ForgotPasswordRateLimiter {

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Bandwidth bandwidth;

    public ForgotPasswordRateLimiter(RateLimitProperties props) {
        RateLimitProperties.Forgot forgot = props.forgot();
        this.bandwidth = Bandwidth.builder()
                .capacity(forgot.maxAttempts())
                .refillIntervally(forgot.maxAttempts(), Duration.ofMinutes(forgot.windowMinutes()))
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
