package com.majstr.backend.feature;

import com.majstr.backend.entity.Plan;
import lombok.Getter;

/**
 * Thrown by {@link FeatureGuard#requireFeature} when the user's plan does
 * not grant the requested feature. {@code GlobalExceptionHandler} maps it
 * to 403 with a user-facing Ukrainian message that suggests the cheapest
 * upgrade.
 */
@Getter
public class FeatureNotAvailableException extends RuntimeException {

    private final Feature feature;
    private final Plan currentPlan;
    private final Plan requiredPlan;

    public FeatureNotAvailableException(Feature feature, Plan currentPlan) {
        super(buildMessage(feature, currentPlan));
        this.feature = feature;
        this.currentPlan = currentPlan;
        this.requiredPlan = PlanConfig.minimumPlanFor(feature);
    }

    private static String buildMessage(Feature feature, Plan currentPlan) {
        Plan required = PlanConfig.minimumPlanFor(feature);
        return "Функція " + feature.name()
                + " недоступна на плані " + currentPlan.name()
                + ". Доступно у плані " + required.name() + " або вищому.";
    }
}
