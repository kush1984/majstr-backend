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
 * Per-account cap on fiscal-QR reads (default 120/hour), counted SEPARATELY from
 * {@link ReceiptScanRateLimiter} (master decision, 2026-08-24: «рахуємо кюар шлях окремо і не
 * міняємо ліміти»).
 *
 * <p>The two paths are not the same kind of spend. A QR read costs no model call — since the
 * receipts-batch iteration it is the free first rung inside «додати чек з фото», tried
 * automatically on every picked photo, so a batch of ten receipts spends ten of these while
 * spending zero recognitions. Put them in one bucket and a single photo batch would eat the budget
 * for the pass that actually costs money. Hence a separate, more generous limit.</p>
 *
 * <p>Process-local {@link ConcurrentHashMap}, same single-node limitation as the other limiters.</p>
 */
@Component
public class QrScanRateLimiter {

    private final ConcurrentMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();
    private final Bandwidth bandwidth;

    public QrScanRateLimiter(RateLimitProperties props) {
        int maxPerHour = props.qrScan().maxPerHour();
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
