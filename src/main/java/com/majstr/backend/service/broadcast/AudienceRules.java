package com.majstr.backend.service.broadcast;

import com.majstr.backend.entity.BroadcastSegment;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Places a master in the ONE segment for the earliest funnel step he has not passed, or none.
 *
 * <p>Disjoint by construction — the order of the checks IS the rule. A master with a near-empty
 * estimate who has also been away a month is {@link BroadcastSegment#EMPTY_ESTIMATE}, never also
 * {@link BroadcastSegment#INACTIVE}: one letter about the thing he is actually stuck on.</p>
 *
 * <p>Who may be in ANY segment (a USER, on FREE, not unsubscribed) is the query's job
 * ({@code UserRepository#findBroadcastAudience}); this only reads the funnel facts.</p>
 */
public final class AudienceRules {

    /** An estimate with at least this many lines is a real one. */
    public static final int FULL_ESTIMATE_LINES = 5;
    /** Don't write to somebody who registered an hour ago — give him a few days to get going. */
    public static final Duration SETTLE = Duration.ofDays(3);
    public static final Duration INACTIVE_AFTER = Duration.ofDays(30);

    private AudienceRules() {}

    public record Master(UUID id, String fullName, String email, boolean emailVerified,
                         Instant registeredAt, Instant lastActiveAt,
                         long objects, long estimates, long maxEstimateLines) {

        /** Never-seen falls back to the registration: the tracker stamps only authenticated calls. */
        public Instant lastSeen() {
            return lastActiveAt != null ? lastActiveAt : registeredAt;
        }
    }

    public static Optional<BroadcastSegment> segmentOf(Master m, Instant now) {
        boolean settled = !m.registeredAt().isAfter(now.minus(SETTLE));
        if (!m.emailVerified()) {
            return settled ? Optional.of(BroadcastSegment.UNVERIFIED) : Optional.empty();
        }
        if (m.objects() == 0) {
            return settled ? Optional.of(BroadcastSegment.NO_OBJECT) : Optional.empty();
        }
        if (m.estimates() == 0) {
            return settled ? Optional.of(BroadcastSegment.NO_ESTIMATE) : Optional.empty();
        }
        if (m.maxEstimateLines() < FULL_ESTIMATE_LINES) {
            return settled ? Optional.of(BroadcastSegment.EMPTY_ESTIMATE) : Optional.empty();
        }
        return m.lastSeen().isBefore(now.minus(INACTIVE_AFTER))
                ? Optional.of(BroadcastSegment.INACTIVE)
                : Optional.empty();
    }
}
