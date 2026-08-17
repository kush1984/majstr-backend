package com.majstr.backend.dto;

import com.majstr.backend.entity.WorkActKind;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Edit a DRAFT/SENT act's header (a signed act is immutable → 409). */
public record WorkActUpdateRequest(
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
