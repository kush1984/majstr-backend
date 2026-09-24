package com.majstr.backend.service;

import com.majstr.backend.config.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Rate limit for client questions asked through ANY public portal (legacy {@code ?t}, signature
 * {@code ?p}, economy {@code ?e}, act {@code ?a}), keyed on IP + portal token — the same shape as
 * {@link MessageLinkRateLimiter}, and for the same reason: these endpoints WRITE (a stored message
 * plus a push notification straight to the master's phone), so the blanket 30/min read limit of
 * {@link PortalRateLimiter} is not the right cap. Keyed on the pair so one address cannot spray
 * every portal, and one leaked token cannot be used as a notification firehose from many addresses.
 */
@Component
public class QuestionRateLimiter {

    private final BucketRegistry<String> buckets;
    private final Bandwidth bandwidth;

    public QuestionRateLimiter(RateLimitProperties props) {
        RateLimitProperties.Question question = props.question();
        this.bandwidth = Bandwidth.builder()
                .capacity(question.maxAttempts())
                .refillIntervally(question.maxAttempts(), Duration.ofMinutes(question.windowMinutes()))
                .build();
        // The map that used to sit here never dropped a key (B-22).
        this.buckets = new BucketRegistry<>(this.bandwidth, Duration.ofMinutes(question.windowMinutes()));
    }

    public ConsumeResult tryConsume(String key) {
        Bucket bucket = buckets.get(key);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new ConsumeResult(true, 0L);
        }
        long retryAfter = Math.max(1L, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        return new ConsumeResult(false, retryAfter);
    }

    public record ConsumeResult(boolean allowed, long retryAfterSeconds) {}
}
