package com.majstr.backend.dto;

import com.majstr.backend.entity.Plan;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * An admin's plan change (V199). The term of a paid plan is EITHER {@code months} — added to what is
 * left of the same plan's current term, or counted from today — OR {@code until}, the last day PRO
 * is on (Kyiv); neither = no end. Ignored for FREE.
 */
public record PlanUpdateRequest(
        @NotNull Plan plan,
        @Min(1) @Max(3) Integer months,
        LocalDate until
) {}
