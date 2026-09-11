package com.majstr.backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * «Моя норма»: how much material one unit of this work consumes, in the master's own experience.
 *
 * <p>Only the coefficient travels. Everything else about the norm — which material, which unit,
 * what the figure multiplies — belongs to the shipped row he is correcting, and letting a request
 * restate it would let one master's screen redefine another's arithmetic.</p>
 */
public record MaterialNormUpdateRequest(
        @NotNull
        @DecimalMin(value = "0.0001", message = "qtyPerUnit must be greater than 0")
        @Digits(integer = 11, fraction = 4)
        BigDecimal qtyPerUnit
) {}
