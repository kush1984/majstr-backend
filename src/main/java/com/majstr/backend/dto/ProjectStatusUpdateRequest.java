package com.majstr.backend.dto;

import com.majstr.backend.entity.ProjectStatus;
import jakarta.validation.constraints.NotNull;

public record ProjectStatusUpdateRequest(
        @NotNull ProjectStatus status
) {}
