package com.majstr.backend.dto;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.feature.Limit;
import com.majstr.backend.feature.PlanConfig;

/**
 * The current user's plan quotas, so the PWA can disable "create" buttons
 * before the user starts (prevention, not a reject-at-the-end). {@code null}
 * cap means unlimited. {@code projectsUsed} is the LIFETIME object count (objects ever created,
 * never decremented on delete) — the basis of the FREE object cap, so the UI gate and the
 * over-limit hint match the server. The server-side checks in {@code LimitService} remain the
 * source of truth — this is a UX hint only.
 */
public record PlanLimitsResponse(
        Plan plan,
        Integer maxProjects,
        int projectsUsed,
        Integer maxEstimatesPerProject,
        Integer maxPhotosPerObject,
        Integer maxReceiptPhotosPerObject
) {
    public static PlanLimitsResponse of(User user) {
        Plan plan = user.getPlan();
        return new PlanLimitsResponse(
                plan,
                nullIfUnlimited(PlanConfig.limit(plan, Limit.MAX_PROJECTS)),
                user.getLifetimeProjectCount(),
                nullIfUnlimited(PlanConfig.limit(plan, Limit.MAX_ESTIMATES_PER_PROJECT)),
                nullIfUnlimited(PlanConfig.limit(plan, Limit.MAX_PHOTOS_PER_OBJECT)),
                nullIfUnlimited(PlanConfig.limit(plan, Limit.MAX_RECEIPT_PHOTOS_PER_OBJECT))
        );
    }

    private static Integer nullIfUnlimited(int value) {
        return value < 0 ? null : value;
    }
}
