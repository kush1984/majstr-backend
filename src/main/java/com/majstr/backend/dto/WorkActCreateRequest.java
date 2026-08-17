package com.majstr.backend.dto;

import com.majstr.backend.entity.WorkActKind;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Create a work-act draft (its header). Line items are set separately via PUT /items — the header
 * is what freezes the act's identity (number, period). All the display toggles default sensibly if
 * omitted.
 */
public record WorkActCreateRequest(
        @NotNull WorkActKind kind,
        @NotNull LocalDate issuedAt,
        @NotNull LocalDate periodFrom,
        @NotNull LocalDate periodTo,
        @Size(max = 120) String place,
        @Size(max = 255) String contractRef,
        String note,
        Boolean showMaterials,
        Boolean showCumulative,
        BigDecimal advanceOffset
) {}
