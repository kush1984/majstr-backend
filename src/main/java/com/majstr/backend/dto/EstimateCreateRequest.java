package com.majstr.backend.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record EstimateCreateRequest(
        LocalDate validUntil,
        @Size(max = 4000) String notes
) {}
