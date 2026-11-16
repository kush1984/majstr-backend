package com.majstr.backend.service.inbox;

import com.majstr.backend.config.BotProperties;
import com.majstr.backend.service.BucketRegistry;
import io.github.bucket4j.Bandwidth;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Per-account daily budget of inbox parses — every bot message and PWA quick add spends one model
 * call, and the bot is on FREE, so this, not a plan gate, is what bounds the spend. Process-local
 * {@link BucketRegistry}, the same single-node limitation as the other limiters.
 */
@Component
public class InboxParseRateLimiter {

    private final BucketRegistry<UUID> buckets;

    public InboxParseRateLimiter(BotProperties props) {
        int perDay = Math.max(1, props.parsesPerDay());
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(perDay)
                .refillIntervally(perDay, Duration.ofDays(1))
                .build();
        this.buckets = new BucketRegistry<>(bandwidth, Duration.ofDays(1));
    }

    public boolean tryConsume(UUID accountId) {
        return buckets.get(accountId).tryConsume(1);
    }
}
