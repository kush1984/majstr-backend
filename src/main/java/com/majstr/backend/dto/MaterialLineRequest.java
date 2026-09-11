package com.majstr.backend.dto;

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
        @NotNull @PositiveOrZero BigDecimal quantity
) {}
