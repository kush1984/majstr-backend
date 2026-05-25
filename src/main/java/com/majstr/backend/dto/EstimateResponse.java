package com.majstr.backend.dto;

import com.majstr.backend.entity.EstimateStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record EstimateResponse(
        UUID id,
        UUID projectId,
        EstimateStatus status,
        LocalDate validUntil,
        String notes,
        Instant createdAt,
        Instant updatedAt,
        List<EstimateItemResponse> items,
        BigDecimal worksSubtotal,
        BigDecimal materialsSubtotal,
        BigDecimal total
) {}
