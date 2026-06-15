package com.majstr.backend.dto;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.feature.Limit;
import com.majstr.backend.feature.PlanConfig;

/**
 * The current user's plan quotas, so the PWA can disable "create" buttons
 * before the user starts (prevention, not a reject-at-the-end). {@code null}
 * means unlimited. The server-side checks in {@code LimitService} remain the
 * source of truth — this is a UX hint only.
 */
public record PlanLimitsResponse(
        Plan plan,
        Integer maxProjects,
        Integer maxEstimatesPerProject
) {
    public static PlanLimitsResponse of(Plan plan) {
        return new PlanLimitsResponse(
                plan,
                nullIfUnlimited(PlanConfig.limit(plan, Limit.MAX_PROJECTS)),
                nullIfUnlimited(PlanConfig.limit(plan, Limit.MAX_ESTIMATES_PER_PROJECT))
        );
    }

    private static Integer nullIfUnlimited(int value) {
        return value < 0 ? null : value;
    }
}
