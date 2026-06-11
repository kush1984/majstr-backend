package com.majstr.backend.exception;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.feature.Limit;
import lombok.Getter;

/**
 * Thrown when a user tries to create a resource beyond what their plan
 * allows. {@code GlobalExceptionHandler} maps this to 403 and builds the
 * user-facing message from the {@code messages} bundle
 * ({@code error.limit.projects} + plural forms) using the carried fields;
 * the exception message itself is English log detail only.
 */
@Getter
public class LimitExceededException extends RuntimeException {

    private final Limit limit;
    private final int maxAllowed;
    private final Plan currentPlan;

    public LimitExceededException(Limit limit, int maxAllowed, Plan currentPlan) {
        super("Limit " + limit.name() + " exceeded: max " + maxAllowed + " on plan " + currentPlan.name());
        this.limit = limit;
        this.maxAllowed = maxAllowed;
        this.currentPlan = currentPlan;
    }
}
