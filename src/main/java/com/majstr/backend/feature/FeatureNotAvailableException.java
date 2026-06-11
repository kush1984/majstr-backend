package com.majstr.backend.feature;

import com.majstr.backend.entity.Plan;
import lombok.Getter;

/**
 * Thrown by {@link FeatureGuard#requireFeature} when the user's plan does
 * not grant the requested feature. {@code GlobalExceptionHandler} maps it to
 * 403 and builds the user-facing message from the {@code messages} bundle
 * ({@code error.feature.unavailable}) using the carried fields; the exception
 * message itself is English log detail only.
 */
@Getter
public class FeatureNotAvailableException extends RuntimeException {

    private final Feature feature;
    private final Plan currentPlan;
    private final Plan requiredPlan;

    public FeatureNotAvailableException(Feature feature, Plan currentPlan) {
        super("Feature " + feature.name() + " not available on plan " + currentPlan.name()
                + " (requires " + PlanConfig.minimumPlanFor(feature).name() + ")");
        this.feature = feature;
        this.currentPlan = currentPlan;
        this.requiredPlan = PlanConfig.minimumPlanFor(feature);
    }
}
