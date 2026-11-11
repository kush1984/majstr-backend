package com.majstr.backend.dto;

import com.majstr.backend.entity.Plan;

import java.time.Instant;
import java.util.List;

/**
 * The admin card's «Активність» block (V199): minutes of use over the last 7 and 30 days, the
 * latest sessions, and the plan changes made from the admin page. A session's {@code endedAt} is
 * its last request, known to within 5 minutes; {@code open} = it may still be going on.
 */
public record AdminUserActivity(
        long minutesLast7Days,
        long minutesLast30Days,
        List<Session> sessions,
        List<Grant> planGrants
) {
    public record Session(Instant startedAt, Instant endedAt, long minutes, boolean open) {}

    public record Grant(Instant at, String by, Plan plan, Integer months, Instant expiresAt) {}
}
