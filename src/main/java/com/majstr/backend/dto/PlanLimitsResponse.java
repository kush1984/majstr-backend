package com.majstr.backend.dto;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.feature.Limit;
import com.majstr.backend.feature.PlanConfig;

import java.math.BigDecimal;

/**
 * The current user's plan quotas, so the PWA can disable "create" buttons
 * before the user starts (prevention, not a reject-at-the-end). {@code null}
 * cap means unlimited. {@code projectsUsed} is the LIFETIME object count (objects ever created,
 * never decremented on delete) — the basis of the FREE object cap, so the UI gate and the
 * over-limit hint match the server. The server-side checks in {@code LimitService} remain the
 * source of truth — this is a UX hint only. {@code trialDays} is the configured length of the one-time
 * PRO trial ({@code app.billing.trial-days}), so an offer can name it without the PWA typing a number.
 *
 * <p>One-off objects (V214): {@code objectCredits} bought and not created yet (a create at the cap spends
 * one), {@code objectPrice} what one costs, {@code objectsBoughtLastMonth} for the «PRO is cheaper» hint,
 * and {@code paidObject} the per-object caps on an object created that way (PRO's).</p>
 */
public record PlanLimitsResponse(
        Plan plan,
        Integer maxProjects,
        int projectsUsed,
        Integer maxEstimatesPerProject,
        Integer maxPhotosPerObject,
        Integer maxReceiptPhotosPerObject,
        int trialDays,
        int objectCredits,
        BigDecimal objectPrice,
        long objectsBoughtLastMonth,
        PaidObjectLimits paidObject
) {
    /** Per-object caps on a one-off paid object; null = unlimited. */
    public record PaidObjectLimits(
            Integer maxEstimatesPerProject,
            Integer maxPhotosPerObject,
            Integer maxReceiptPhotosPerObject
    ) {}

    public static PlanLimitsResponse of(User user, int trialDays, BigDecimal objectPrice, long objectsBoughtLastMonth) {
        Plan plan = user.getPlan();
        return new PlanLimitsResponse(
                plan,
                nullIfUnlimited(PlanConfig.limit(plan, Limit.MAX_PROJECTS)),
                user.getLifetimeProjectCount(),
                nullIfUnlimited(PlanConfig.limit(plan, Limit.MAX_ESTIMATES_PER_PROJECT)),
                nullIfUnlimited(PlanConfig.limit(plan, Limit.MAX_PHOTOS_PER_OBJECT)),
                nullIfUnlimited(PlanConfig.limit(plan, Limit.MAX_RECEIPT_PHOTOS_PER_OBJECT)),
                trialDays,
                user.getObjectCredits(),
                objectPrice,
                objectsBoughtLastMonth,
                new PaidObjectLimits(
                        nullIfUnlimited(PlanConfig.limit(Plan.PRO, Limit.MAX_ESTIMATES_PER_PROJECT)),
                        nullIfUnlimited(PlanConfig.limit(Plan.PRO, Limit.MAX_PHOTOS_PER_OBJECT)),
                        nullIfUnlimited(PlanConfig.limit(Plan.PRO, Limit.MAX_RECEIPT_PHOTOS_PER_OBJECT)))
        );
    }

    private static Integer nullIfUnlimited(int value) {
        return value < 0 ? null : value;
    }
}
