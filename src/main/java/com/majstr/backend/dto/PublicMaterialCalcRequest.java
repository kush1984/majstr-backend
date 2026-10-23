package com.majstr.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * A public calculator's question (`POST /api/public/materials/calc`). The visitor names a
 * CALCULATOR from a white list and answers its questions; WHICH catalog positions stand behind the
 * answers is decided on the server ({@code PublicMaterialCalculatorService}) and never taken from
 * the request.
 *
 * <p>Every bound is a 400: the page is a form, and a figure past it is a typo, not a job.</p>
 */
public record PublicMaterialCalcRequest(
        @NotNull PublicCalculator calculator,
        @NotNull @Valid Inputs inputs
) {

    public enum PublicCalculator { TILE, DRYWALL, PLASTER, SCREED }

    /** The tile formats the library has a laying position for (V146 + V148). */
    public enum TileFormat {
        T100X100, T200X200, T300X300, T300X600, T600X600, T600X1200, T800X1600, T1200X2400,
        MOSAIC, METRO
    }

    /** How the course runs; absent = straight. A diagonal one is the estimate's «по діагоналі» line. */
    public enum TileLayout { STRAIGHT, DIAGONAL }

    public enum DrywallWork { CEILING, WALL, PARTITION }

    /** Gypsum for dry rooms; cement-lime for a bathroom, a reveal, an unheated room. */
    public enum PlasterKind { GYPSUM, CEMENT_LIME }

    /** Absent = by hand. The library has a machine position for gypsum only. */
    public enum PlasterMethod { MANUAL, MACHINE }

    /** A cement screed from bags, or a self-levelling floor. */
    public enum ScreedKind { CEMENT, SELF_LEVELLING }

    public record Inputs(
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("10000") BigDecimal area,
            /** 0-30 %; absent = the calculator's default (10 %). */
            @DecimalMin("0") @DecimalMax("30") BigDecimal wastePercent,
            TileFormat format,
            /** The tile joint, mm; absent = the joint the norm itself assumed. */
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("20") BigDecimal jointMm,
            TileLayout layout,
            /** The m² printed on one box of the tile; absent = no box count. */
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("10") BigDecimal boxArea,
            /**
             * The main material's figure read off the visitor's own bag, in place of ours; absent =
             * ours. kg/m² for the tile adhesive, kg/m² PER MM for a plaster or a screed. Not for
             * DRYWALL, and 0,1-10× our figure (checked by the service).
             */
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000") BigDecimal ownNorm,
            DrywallWork work,
            /**
             * The room's perimeter, m — the ceiling's UD runs along it; for a screed it is optional
             * and, when given, buys the damper tape.
             */
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000") BigDecimal perimeter,
            /** 1 or 2; only a partition has a two-layer position in the library. */
            @Min(1) @Max(2) Integer layers,
            PlasterKind plaster,
            PlasterMethod method,
            ScreedKind screed,
            /**
             * The layer, mm; absent = the chosen position's own suggestion. The bound here is the
             * library's (≤ 150); each kind's narrower one is checked by the service.
             */
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("150") BigDecimal thicknessMm,
            /** Cement screed only: lay a reinforcing mesh under it. */
            Boolean withMesh,
            /** Cement screed only: lay a polyethylene film under it. */
            Boolean withFilm
    ) {}
}
