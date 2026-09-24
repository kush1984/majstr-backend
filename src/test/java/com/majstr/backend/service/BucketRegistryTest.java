package com.majstr.backend.service;

import io.github.bucket4j.Bandwidth;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The eviction ten rate limiters were missing (review B-22). What has to be true is not «the map
 * shrinks» but «shrinking changes no answer»: an entry is only dropped once its bucket has refilled
 * to capacity, which is exactly the state a key gets on its first request.
 */
class BucketRegistryTest {

    private static Bandwidth perMinute(int capacity) {
        return Bandwidth.builder()
                .capacity(capacity)
                .refillIntervally(capacity, Duration.ofMinutes(1))
                .build();
    }

    @Test
    void aKeyKeepsItsBucketWhileItIsBeingUsed() {
        BucketRegistry<String> registry = new BucketRegistry<>(perMinute(2), Duration.ofMinutes(1));

        assertThat(registry.get("a").tryConsume(1)).isTrue();
        assertThat(registry.get("a").tryConsume(1)).isTrue();
        // Third within the window: the map must be the SAME bucket, or the limit is no limit.
        assertThat(registry.get("a").tryConsume(1)).isFalse();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void anIdleKeyIsDroppedAndComesBackFull() {
        BucketRegistry<String> registry = new BucketRegistry<>(perMinute(1), Duration.ofMinutes(1));
        assertThat(registry.get("a").tryConsume(1)).isTrue();
        assertThat(registry.get("a").tryConsume(1)).isFalse(); // spent

        registry.ageAll();
        registry.get("b"); // any call sweeps

        // «a» is gone, which is observationally identical to it having refilled — the licence to
        // evict at all. Dropping a bucket that was still SPENT would be a limiter that forgets.
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.get("a").tryConsume(1)).isTrue();
    }

    /**
     * The six limiters keyed by something a stranger picks — {@code email|ip}, an IP, a portal
     * token — are the reason this exists: their map used to grow with request CONTENT.
     */
    @Test
    void aFloodOfOneOffKeysDoesNotGrowForever() {
        BucketRegistry<String> registry = new BucketRegistry<>(perMinute(5), Duration.ofMinutes(1));
        for (int i = 0; i < 500; i++) {
            registry.get("attacker-" + i + "@example.com|203.0.113.7");
        }
        assertThat(registry.size()).isEqualTo(500);

        registry.ageAll();
        registry.get("someone-real@example.com|203.0.113.8");

        assertThat(registry.size()).isEqualTo(1);
    }
}
