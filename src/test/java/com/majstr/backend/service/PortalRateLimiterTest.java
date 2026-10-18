package com.majstr.backend.service;

import com.majstr.backend.config.RateLimitProperties;
import com.majstr.backend.security.PublicPortalRateLimitFilterTestAccess;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The portal at a meetup: dozens of people behind ONE address (the hall's Wi-Fi, a carrier's CGNAT)
 * open the same link at once. One link is generous, writes stay strict, and only many links from
 * one address hit the ceiling.
 */
class PortalRateLimiterTest {

    private static PortalRateLimiter limiter() {
        RateLimitProperties props = mock(RateLimitProperties.class);
        when(props.portal()).thenReturn(new RateLimitProperties.Portal(300, 1, 120, 10));
        return new PortalRateLimiter(props);
    }

    @Test
    void sixtyReadsOfOneLinkFromOneAddressAllPass() {
        PortalRateLimiter limiter = limiter();
        for (int i = 0; i < 60; i++) {
            assertThat(limiter.tryConsume("203.0.113.7", "TOKEN", false).allowed()).as("read %d", i + 1).isTrue();
        }
    }

    @Test
    void sixHundredDifferentLinksFromOneAddressHitTheCeiling() {
        PortalRateLimiter limiter = limiter();
        int allowed = 0;
        PortalRateLimiter.ConsumeResult firstRefusal = null;
        for (int i = 0; i < 600; i++) {
            PortalRateLimiter.ConsumeResult r = limiter.tryConsume("203.0.113.7", "token-" + i, false);
            if (r.allowed()) {
                allowed++;
            } else if (firstRefusal == null) {
                firstRefusal = r;
            }
        }
        // The refill is GREEDY — one more token every 200 ms at 300/min — so a slow machine running
        // this loop legitimately earns a few beyond the 300 (CI did). The ceiling still bites.
        assertThat(allowed).isBetween(300, 330);
        assertThat(firstRefusal).isNotNull();
        assertThat(firstRefusal.retryAfterSeconds()).isBetween(1L, 60L);
        // Another address is untouched by it.
        assertThat(limiter.tryConsume("198.51.100.9", "token-0", false).allowed()).isTrue();
    }

    @Test
    void writesOnOneLinkStayStrict_readsAreNotSpentByThem() {
        PortalRateLimiter limiter = limiter();
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryConsume("203.0.113.7", "TOKEN", true).allowed()).isTrue();
        }
        PortalRateLimiter.ConsumeResult eleventh = limiter.tryConsume("203.0.113.7", "TOKEN", true);
        assertThat(eleventh.allowed()).isFalse();
        assertThat(eleventh.retryAfterSeconds()).isPositive();
        assertThat(limiter.tryConsume("203.0.113.7", "TOKEN", false).allowed()).isTrue();
    }

    @Test
    void theLinkIsReadOffThePath() {
        assertThat(PublicPortalRateLimitFilterTestAccess.linkToken("/api/public/portal/AbC_1/estimates/x/sign"))
                .isEqualTo("AbC_1");
        assertThat(PublicPortalRateLimitFilterTestAccess.linkToken("/api/public/estimates/tok")).isEqualTo("tok");
        assertThat(PublicPortalRateLimitFilterTestAccess.linkToken("/api/public/monitoring")).isNull();
    }
}
