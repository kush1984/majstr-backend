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
 * Per-account cap on act-receipt recognition (default 30/hour). Unlike every other LLM flow, the
 * meta pass of {@code POST /api/acts/{id}/receipts/recognize} is reachable on FREE (master
 * decision, 2026-08-23: reading a footer is what turns a photographed slip into a receipt row,
 * while the item table stays PRO). That makes it the first model call an unpaid account can spend
 * our money on, and recognition persists nothing — so no business counter bounds it and this cap
 * has to. Generous on purpose: a master photographing a day's receipts stays far under it.
 *
 * <p>Process-local {@link ConcurrentHashMap}, same single-node limitation as the other limiters.</p>
 */
@Component
public class ReceiptScanRateLimiter {

    private final ConcurrentMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();
    private final Bandwidth bandwidth;

    public ReceiptScanRateLimiter(RateLimitProperties props) {
        int maxPerHour = props.receiptScan().maxPerHour();
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
