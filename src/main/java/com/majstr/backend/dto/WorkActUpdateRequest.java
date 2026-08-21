package com.majstr.backend.dto;

import com.majstr.backend.entity.WorkActKind;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Edit a DRAFT/SENT act's header (a signed act is immutable → 409). */
public record WorkActUpdateRequest(
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
        Boolean receiptsToExpenses,
        // Same bound as the create request — see WorkActCreateRequest#advanceOffset.
        @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal advanceOffset
) {}
