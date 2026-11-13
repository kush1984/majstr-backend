package com.majstr.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * A tiling line's layout as the master accepted it (V203). The counts are the PWA engine's
 * (`src/lib/tileLayout.ts`): the server bounds them and stores them, it does not lay tiles out.
 * The format may be given either way round; it is stored short side first.
 *
 * @param params the layout's inputs as JSON — surfaces, openings, patterns, starts — kept only so
 *               «Перерахувати» reopens them; never read by the server
 */
public record TileLayoutRequest(
        @NotNull @Min(20) @Max(3600) Integer tileWidthMm,
        @NotNull @Min(20) @Max(3600) Integer tileHeightMm,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("10000") @Digits(integer = 5, fraction = 3)
        BigDecimal laidArea,
        @NotNull @Min(1) @Max(1_000_000) Integer minTiles,
        @NotNull @Min(1) @Max(1_000_000) Integer maxTiles,
        @NotNull @Min(0) @Max(100_000) Integer spare,
        @NotBlank @Size(max = 32768) String params,
        Boolean measurementsDeclined
) {}
