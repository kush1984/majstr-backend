package com.majstr.backend.dto;

import com.majstr.backend.entity.Plan;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * An admin's plan change. {@code months} is the term of a paid plan (V199): added to what is left
 * of the same plan's current term, or counted from today; absent = no end. Ignored for FREE.
 */
public record PlanUpdateRequest(
        @NotNull Plan plan,
        @Min(1) @Max(3) Integer months
) {}
