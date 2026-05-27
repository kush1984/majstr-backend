package com.majstr.backend.exception;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.feature.Limit;
import lombok.Getter;

/**
 * Thrown when a user tries to create a resource beyond what their plan
 * allows. {@code GlobalExceptionHandler} maps this to 403 with a
 * Ukrainian message that names the limit and suggests upgrading.
 */
@Getter
public class LimitExceededException extends RuntimeException {

    private final Limit limit;
    private final int maxAllowed;
    private final Plan currentPlan;

    public LimitExceededException(Limit limit, int maxAllowed, Plan currentPlan) {
        super(buildMessage(maxAllowed));
        this.limit = limit;
        this.maxAllowed = maxAllowed;
        this.currentPlan = currentPlan;
    }

    private static String buildMessage(int maxAllowed) {
        return "Ви досягли ліміту у " + maxAllowed + " " + projectsForm(maxAllowed)
                + " на безкоштовному плані. Оновіть до PRO для необмеженої кількості об'єктів.";
    }

    /** Ukrainian noun forms for "об'єкт": 1 → об'єкт, 2-4 → об'єкти, 5+ → об'єктів. */
    private static String projectsForm(int n) {
        int mod10 = Math.abs(n) % 10;
        int mod100 = Math.abs(n) % 100;
        if (mod10 == 1 && mod100 != 11) return "об'єкт";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "об'єкти";
        return "об'єктів";
    }
}
