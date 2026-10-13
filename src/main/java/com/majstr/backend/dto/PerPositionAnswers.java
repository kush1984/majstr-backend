package com.majstr.backend.dto;

/**
 * The per-POSITION figures the calculator's GET carries on its query string, one compact scalar per
 * question — «uuid:0.4,uuid:0.55» (see {@code MaterialCalculatorService.parsePerPosition}).
 *
 * <p>Six fields and not one map, by design: a single line can be asked two questions (a box's
 * розгортка and a layer's thickness), and a shared map would answer one with the other's number.
 * The record only groups them on the way into the service.</p>
 *
 * @param sections       a box's розгортка, metres (V131)
 * @param thicknesses    a layer's thickness, millimetres (V137)
 * @param steps          an underfloor loop's spacing, millimetres (V152)
 * @param lengths        a riser's length, metres (V168)
 * @param reinforcements a concrete's rebar ratio, kg/m³ (V168)
 * @param chaseSections  a chase's cross-section, cm² (V168)
 */
public record PerPositionAnswers(
        String sections,
        String thicknesses,
        String steps,
        String lengths,
        String reinforcements,
        String chaseSections
) {
    public static final PerPositionAnswers NONE = new PerPositionAnswers(null, null, null, null, null, null);
}
