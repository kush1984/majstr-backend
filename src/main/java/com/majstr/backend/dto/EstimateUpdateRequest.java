package com.majstr.backend.dto;

import com.majstr.backend.entity.EstimateStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record EstimateUpdateRequest(
        @NotNull EstimateStatus status,
        LocalDate validUntil,
        @Size(max = 4000) String notes
) {}
