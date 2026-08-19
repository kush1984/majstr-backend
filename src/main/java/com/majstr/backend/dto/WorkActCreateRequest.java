package com.majstr.backend.dto;

import com.majstr.backend.entity.WorkActKind;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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
        @Size(max = 120) String title,
        @NotNull LocalDate issuedAt,
        @NotNull LocalDate periodFrom,
        @NotNull LocalDate periodTo,
        @Size(max = 120) String place,
        @Size(max = 255) String contractRef,
        String note,
        Boolean showMaterials,
        Boolean showCumulative,
        // Never negative: a negative "advance" would INFLATE «До сплати» while the totals table
        // hides the advance row (it only renders when > 0) — the PDF would contradict itself.
        @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal advanceOffset
) {}
