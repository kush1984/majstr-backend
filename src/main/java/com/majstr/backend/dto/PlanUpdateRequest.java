package com.majstr.backend.dto;

import com.majstr.backend.entity.Plan;
import jakarta.validation.constraints.NotNull;

public record PlanUpdateRequest(
        @NotNull Plan plan
) {}
