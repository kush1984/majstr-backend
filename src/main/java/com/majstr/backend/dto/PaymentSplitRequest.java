package com.majstr.backend.dto;

import com.majstr.backend.entity.PaymentSplitPreset;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/** {@code customPercents} is required (and must sum to 100) only when {@code preset ==
 *  CUSTOM}; ignored otherwise — the fixed presets carry their own percents. */
public record PaymentSplitRequest(
        @NotNull PaymentSplitPreset preset,
        List<BigDecimal> customPercents
) {}
