package com.majstr.backend.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line as the master left it on the result screen — every number there is editable, so what
 * comes back is what he decided, not what we computed. The server does not re-derive it.
 */
public record MaterialLineRequest(
        @NotNull UUID materialId,
        // numeric(15,3) on the column, so an unbounded figure died as a 500 on the DB (B-18).
        @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 3) BigDecimal quantity
) {}
