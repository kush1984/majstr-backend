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
 * Per-account cap on dictated-position parses (default 60/hour). Same reasoning as
 * {@link ReceiptScanRateLimiter}: {@code POST /api/estimates/{id}/dictation/parse} spends a model
 * call, is reachable on FREE (dictation cut 0 ships ungated on purpose) and persists nothing, so
 * no business counter bounds how often it runs — this does.
 *
 * <p>Its own bucket rather than a share of the receipt ones (same decision as
 * {@link QrScanRateLimiter}): the three answer different questions, and a day spent photographing
 * receipts must never be the reason dictation stops working.</p>
 *
 * <p>Process-local {@link ConcurrentHashMap}, same single-node limitation as the other limiters.</p>
 */
@Component
public class DictationRateLimiter {

    private final ConcurrentMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();
    private final Bandwidth bandwidth;

    public DictationRateLimiter(RateLimitProperties props) {
        int maxPerHour = props.dictation().maxPerHour();
        this.bandwidth = Bandwidth.builder()
                .capacity(maxPerHour)
                .refillIntervally(maxPerHour, Duration.ofHours(1))
                .build();
    }

    public ReceiptScanRateLimiter.ConsumeResult tryConsume(UUID accountId) {
        Bucket bucket = buckets.computeIfAbsent(accountId, k -> Bucket.builder().addLimit(bandwidth).build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new ReceiptScanRateLimiter.ConsumeResult(true, 0L);
        }
        long retryAfterSeconds = Math.max(1L, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        return new ReceiptScanRateLimiter.ConsumeResult(false, retryAfterSeconds);
    }
}
