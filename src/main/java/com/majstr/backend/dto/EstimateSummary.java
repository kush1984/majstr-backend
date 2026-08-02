package com.majstr.backend.dto;

import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateStatus;

import java.math.BigDecimal;
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
        Instant updatedAt,
        boolean countInEconomy,
        /**
         * Set when this estimate is a marked-up COPY of another. The list uses it to say what the
         * economy actually takes from it — «в економіку йде тільки націнка +15%» — because the
         * tick beside it means "counts", and without the note a master would reasonably read that
         * as the whole client total being his earnings. Null on an ordinary estimate.
         */
        BigDecimal markupPercent,
        /**
         * The estimate this one was copied from. The client needs the raw link, not a
         * {@code hasDuplicates} flag: it already holds the object's whole estimate list, so it can
         * see both sides of the pair from this alone — and the PARENT is the row that needs a note
         * saying its crew prices are not earnings.
         */
        UUID duplicatedFromId
) {
    public static EstimateSummary from(Estimate estimate) {
        return new EstimateSummary(
                estimate.getId(),
                estimate.getProject().getId(),
                estimate.getName(),
                estimate.getStatus(),
                estimate.getValidUntil(),
                estimate.getCreatedAt(),
                estimate.getUpdatedAt(),
                estimate.isCountInEconomy(),
                estimate.getMarkupPercent(),
                estimate.getDuplicatedFromId()
        );
    }
}
