package com.majstr.backend.dto;

import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Lightweight view for list endpoints that returns estimates without
 * loading their items.
 */
public record EstimateSummary(
        UUID id,
        UUID projectId,
        String name,
        EstimateStatus status,
        LocalDate validUntil,
        Instant createdAt,
        Instant updatedAt
) {
    public static EstimateSummary from(Estimate estimate) {
        return new EstimateSummary(
                estimate.getId(),
                estimate.getProject().getId(),
                estimate.getName(),
                estimate.getStatus(),
                estimate.getValidUntil(),
                estimate.getCreatedAt(),
                estimate.getUpdatedAt()
        );
    }
}
