package com.majstr.backend.service.broadcast;

import com.majstr.backend.entity.BroadcastSegment;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AudienceRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final Instant LONG_AGO = NOW.minus(Duration.ofDays(90));

    @Test
    void eachFunnelStepHasItsOwnSegment() {
        assertThat(segment(false, LONG_AGO, NOW, 0, 0, 0)).contains(BroadcastSegment.UNVERIFIED);
        assertThat(segment(true, LONG_AGO, NOW, 0, 0, 0)).contains(BroadcastSegment.NO_OBJECT);
        assertThat(segment(true, LONG_AGO, NOW, 1, 0, 0)).contains(BroadcastSegment.NO_ESTIMATE);
        assertThat(segment(true, LONG_AGO, NOW, 1, 2, 4)).contains(BroadcastSegment.EMPTY_ESTIMATE);
        assertThat(segment(true, LONG_AGO, NOW.minus(Duration.ofDays(31)), 1, 1, 5)).contains(BroadcastSegment.INACTIVE);
    }

    @Test
    void anActiveMasterWithARealEstimateIsInNoSegment() {
        assertThat(segment(true, LONG_AGO, NOW.minus(Duration.ofDays(2)), 3, 4, 40)).isEmpty();
    }

    @Test
    void theEarliestStuckStepWins_soSegmentsNeverOverlap() {
        // Away for months AND an empty estimate: one letter, about the estimate.
        assertThat(segment(true, LONG_AGO, LONG_AGO, 1, 1, 4)).contains(BroadcastSegment.EMPTY_ESTIMATE);
        // Unverified but otherwise well along: the address comes first.
        assertThat(segment(false, LONG_AGO, LONG_AGO, 2, 2, 30)).contains(BroadcastSegment.UNVERIFIED);
    }

    @Test
    void fiveLinesIsARealEstimate_fourIsNot() {
        Instant away = NOW.minus(Duration.ofDays(40));
        assertThat(segment(true, LONG_AGO, away, 1, 1, 4)).contains(BroadcastSegment.EMPTY_ESTIMATE);
        assertThat(segment(true, LONG_AGO, away, 1, 1, 5)).contains(BroadcastSegment.INACTIVE);
    }

    @Test
    void aNewcomerGetsThreeDaysBeforeAnyNudge() {
        Instant twoDaysAgo = NOW.minus(Duration.ofDays(2));
        assertThat(segment(false, twoDaysAgo, twoDaysAgo, 0, 0, 0)).isEmpty();
        assertThat(segment(true, twoDaysAgo, twoDaysAgo, 0, 0, 0)).isEmpty();
        assertThat(segment(true, twoDaysAgo, twoDaysAgo, 1, 1, 1)).isEmpty();
        Instant threeDaysAgo = NOW.minus(Duration.ofDays(3));
        assertThat(segment(true, threeDaysAgo, threeDaysAgo, 0, 0, 0)).contains(BroadcastSegment.NO_OBJECT);
    }

    @Test
    void neverSeenFallsBackToTheRegistrationDate() {
        assertThat(segment(true, NOW.minus(Duration.ofDays(31)), null, 1, 1, 9)).contains(BroadcastSegment.INACTIVE);
        assertThat(segment(true, NOW.minus(Duration.ofDays(10)), null, 1, 1, 9)).isEmpty();
    }

    private static Optional<BroadcastSegment> segment(boolean verified, Instant registered, Instant lastActive,
                                                      long objects, long estimates, long maxLines) {
        return AudienceRules.segmentOf(new AudienceRules.Master(UUID.randomUUID(), "Петро", "p@t.ua", verified,
                registered, lastActive, objects, estimates, maxLines), NOW);
    }
}
