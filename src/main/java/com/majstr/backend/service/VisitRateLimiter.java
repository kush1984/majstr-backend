package com.majstr.backend.service;

import io.github.bucket4j.Bandwidth;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Per-IP bucket for the visit beacon. Generous, since a visitor clicking around the landing sends a
 * row per page; its job is only to keep one address from filling the table. A stranger picks the
 * key, hence {@link BucketRegistry} (B-22).
 */
@Component
public class VisitRateLimiter {

    static final int MAX_PER_MINUTE = 60;

    private final BucketRegistry<String> buckets = new BucketRegistry<>(
            Bandwidth.builder()
                    .capacity(MAX_PER_MINUTE)
                    .refillIntervally(MAX_PER_MINUTE, Duration.ofMinutes(1))
                    .build(),
            Duration.ofMinutes(1));

    public boolean tryConsume(String ip) {
        return buckets.get(ip).tryConsume(1);
    }
}
