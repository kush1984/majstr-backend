package com.majstr.backend.service;

import com.majstr.backend.config.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Caps registrations per client IP. Unlike login (keyed by email+IP), there is
 * no account yet, so the IP is the only stable key. Same single-node in-memory
 * limitation as the other limiters.
 *
 * <p>A REFUSED attempt (400: a typo in the address, a form field) gives its try back
 * ({@link #refund}): a person correcting his email keeps his five. The refunds have their
 * own cap per window, so a script cycling bad addresses still runs out, and each of them
 * may cost a DNS lookup.</p>
 */
@Component
public class RegisterRateLimiter {

    static final int REFUNDS_PER_WINDOW = 20;

    private final BucketRegistry<String> buckets;
    private final BucketRegistry<String> refunds;
    private final Bandwidth bandwidth;

    public RegisterRateLimiter(RateLimitProperties props) {
        RateLimitProperties.Register register = props.register();
        Duration window = Duration.ofMinutes(register.windowMinutes());
        this.bandwidth = Bandwidth.builder()
                .capacity(register.maxAttempts())
                .refillIntervally(register.maxAttempts(), window)
                .build();
        // The map that used to sit here never dropped a key (B-22).
        this.buckets = new BucketRegistry<>(this.bandwidth, window);
        this.refunds = new BucketRegistry<>(Bandwidth.builder()
                .capacity(REFUNDS_PER_WINDOW)
                .refillIntervally(REFUNDS_PER_WINDOW, window)
                .build(), window);
    }

    /** The attempt was refused, so it does not count, while the refunds last. */
    public void refund(String key) {
        if (refunds.get(key).tryConsume(1)) {
            buckets.get(key).addTokens(1);
        }
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
