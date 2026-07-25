package com.majstr.backend.service.measurement;

import com.majstr.backend.entity.MeasurementType;
import com.majstr.backend.exception.MeasurementException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The three server-side formulas + validation. No Spring context. */
class MeasurementCalcTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final MeasurementCalc calc = new MeasurementCalc(mapper);

    private JsonNode node(String json) {
        return mapper.readTree(json);
    }

    @Test
    void surface_sumsAreasMinusOpenings() {
        // 5.31×3.69 − 0.9×2.1 = 19.5939 − 1.89 = 17.7039 → 17.704
        JsonNode p = node("""
                {"segments":[{"l":5.31,"w":3.69}],"openings":[{"w":0.9,"h":2.1,"n":1}]}""");
        assertThat(calc.compute(MeasurementType.SURFACE, p)).isEqualByComparingTo("17.704");
    }

    @Test
    void partition_defaultFacesTwoSidesPlusEnd() {
        // faces omitted → default left+right+end (no top): HW·2 + HD = 2.5·1.2·2 + 2.5·0.3 = 6.75
        JsonNode p = node("""
                {"height":2.5,"width":1.2,"depth":0.3}""");
        assertThat(calc.compute(MeasurementType.PARTITION, p)).isEqualByComparingTo("6.750");
    }

    @Test
    void partition_respectsFaceToggles() {
        // all four faces: + WD (1.2·0.3=0.36) → 7.11
        JsonNode p = node("""
                {"height":2.5,"width":1.2,"depth":0.3,"faces":{"left":true,"right":true,"end":true,"top":true}}""");
        assertThat(calc.compute(MeasurementType.PARTITION, p)).isEqualByComparingTo("7.110");
    }

    @Test
    void linear_perimeterSidesTimesCount() {
        // default sides left+right+top (no bottom): H+H+W = 1.5+1.5+1.0 = 4.0; ×3 = 12.0
        JsonNode p = node("""
                {"height":1.5,"width":1.0,"qty":3}""");
        assertThat(calc.compute(MeasurementType.LINEAR, p)).isEqualByComparingTo("12.000");
    }

    @Test
    void linear_lengthMode_isJustWidthTimesQty_noRevealSides() {
        // A plain running length (skirting / imported reveal total): width × qty, sides ignored.
        JsonNode p = node("""
                {"mode":"length","width":8.535,"qty":1}""");
        assertThat(calc.compute(MeasurementType.LINEAR, p)).isEqualByComparingTo("8.535");
        JsonNode twice = node("""
                {"mode":"length","width":3.2,"qty":2}""");
        assertThat(calc.compute(MeasurementType.LINEAR, twice)).isEqualByComparingTo("6.400");
    }

    @Test
    void negativeDimensionIsRejected() {
        JsonNode p = node("""
                {"height":-1,"width":1.0,"qty":1}""");
        assertThatThrownBy(() -> calc.compute(MeasurementType.LINEAR, p))
                .isInstanceOf(MeasurementException.class);
    }

    @Test
    void neverNegative_openingsBiggerThanSurface() {
        JsonNode p = node("""
                {"segments":[{"l":1,"w":1}],"openings":[{"w":2,"h":2,"n":1}]}""");
        assertThat(calc.compute(MeasurementType.SURFACE, p)).isEqualByComparingTo("0.000");
    }

    // ---- shaped planes --------------------------------------------------------
    // The expected values are pinned to the SAME numbers as the PWA's shapes.test.ts,
    // so a drift between the two geometry implementations fails a test on both sides.

    @Test
    void surface_directArea() {
        // Imported from a room schedule: the area IS the value — pinned to shapes.test.ts «direct».
        JsonNode p = node("""
                {"unit":"M","segments":[{"shape":"direct","mode":"","values":{"s":30.33}}],"openings":[]}""");
        assertThat(calc.compute(MeasurementType.SURFACE, p)).isEqualByComparingTo("30.330");
    }

    @Test
    void surface_lshape() {
        // Г-подібна: 5×4 м gabarits with a 1,5×2 м corner cut → 20 − 3 = 17 m².
        // Pinned to the SAME numbers as the PWA's shapes.test.ts «lshape».
        JsonNode p = node("""
                {"unit":"M","segments":[{"shape":"lshape","mode":"d",
                 "values":{"A":5,"B":4,"a":1.5,"b":2}}],"openings":[]}""");
        assertThat(calc.compute(MeasurementType.SURFACE, p)).isEqualByComparingTo("17.000");
    }

    @Test
    void surface_lshapeRejectsCutBiggerThanTheRoom() {
        JsonNode p = node("""
                {"unit":"M","segments":[{"shape":"lshape","mode":"d",
                 "values":{"A":5,"B":4,"a":6,"b":2}}],"openings":[]}""");
        assertThatThrownBy(() -> calc.compute(MeasurementType.SURFACE, p))
                .isInstanceOf(MeasurementException.class);
    }

    @Test
    void surface_shapedRectangleInCentimetres() {
        // 300 × 250 cm = 75000 cm² = 7.5 m²
        JsonNode p = node("""
                {"unit":"CM","segments":[{"shape":"rect","mode":"d","values":{"a":300,"b":250}}],"openings":[]}""");
        assertThat(calc.compute(MeasurementType.SURFACE, p)).isEqualByComparingTo("7.500");
    }

    @Test
    void surface_trapezoid() {
        // (180 + 300) / 2 × 200 = 48000 cm² = 4.8 m²
        JsonNode p = node("""
                {"unit":"CM","segments":[{"shape":"trap","mode":"d","values":{"a":180,"b":300,"h":200}}],"openings":[]}""");
        assertThat(calc.compute(MeasurementType.SURFACE, p)).isEqualByComparingTo("4.800");
    }

    @Test
    void surface_mansardBothModes() {
        // symmetric: 300 × (150 + 260) / 2 = 61500 cm² = 6.15 m²
        JsonNode sym = node("""
                {"unit":"CM","segments":[{"shape":"attic","mode":"sym","values":{"a":300,"b":150,"h":260}}],"openings":[]}""");
        assertThat(calc.compute(MeasurementType.SURFACE, sym)).isEqualByComparingTo("6.150");

        // asymmetric (different side walls): 62250 cm² = 6.225 m²
        JsonNode asym = node("""
                {"unit":"CM","segments":[{"shape":"attic","mode":"asym","values":{"a":300,"b":120,"c":190,"h":260}}],"openings":[]}""");
        assertThat(calc.compute(MeasurementType.SURFACE, asym)).isEqualByComparingTo("6.225");
    }

    @Test
    void surface_triangleBothModes() {
        // base + height: 300 × 200 / 2 = 30000 cm² = 3 m²
        JsonNode bh = node("""
                {"unit":"CM","segments":[{"shape":"tri","mode":"bh","values":{"b":300,"h":200}}],"openings":[]}""");
        assertThat(calc.compute(MeasurementType.SURFACE, bh)).isEqualByComparingTo("3.000");

        // three sides (Heron): the 3-4-5 triangle, in metres
        JsonNode sss = node("""
                {"unit":"M","segments":[{"shape":"tri","mode":"sss","values":{"a":3,"b":4,"c":5}}],"openings":[]}""");
        assertThat(calc.compute(MeasurementType.SURFACE, sss)).isEqualByComparingTo("6.000");
    }

    @Test
    void surface_cutCorner() {
        // 88×88 − 40.8×40.8/2 = 6911.68 cm² = 0.691 m²
        JsonNode p = node("""
                {"unit":"CM","segments":[{"shape":"cut","mode":"d","values":{"a":88,"b":88,"c":47.2,"d":47.2}}],"openings":[]}""");
        assertThat(calc.compute(MeasurementType.SURFACE, p)).isEqualByComparingTo("0.691");
    }

    @Test
    void surface_mixesShapesAndSubtractsOpeningsInTheSameUnit() {
        // A mansard ceiling: 2 rectangles + a triangle, minus a 90×140 window.
        // 2×(200×300) + 400×150/2 = 120000 + 30000 = 150000 cm² = 15 m²; window 1.26 m²
        JsonNode p = node("""
                {"unit":"CM","segments":[
                   {"shape":"rect","mode":"d","values":{"a":200,"b":300}},
                   {"shape":"rect","mode":"d","values":{"a":200,"b":300}},
                   {"shape":"tri","mode":"bh","values":{"b":400,"h":150}}],
                 "openings":[{"w":90,"h":140,"n":1}]}""");
        assertThat(calc.compute(MeasurementType.SURFACE, p)).isEqualByComparingTo("13.740");
    }

    @Test
    void surface_legacyAndShapedPlanesCoexist() {
        // A pre-shapes row (metres, no shape) alongside a shaped one — unit absent = metres.
        JsonNode p = node("""
                {"segments":[{"l":2,"w":3},{"shape":"tri","mode":"bh","values":{"b":4,"h":1.5}}],"openings":[]}""");
        assertThat(calc.compute(MeasurementType.SURFACE, p)).isEqualByComparingTo("9.000");
    }

    @Test
    void surface_impossibleTriangleIsRejected() {
        JsonNode p = node("""
                {"unit":"M","segments":[{"shape":"tri","mode":"sss","values":{"a":1,"b":2,"c":5}}],"openings":[]}""");
        assertThatThrownBy(() -> calc.compute(MeasurementType.SURFACE, p))
                .isInstanceOf(MeasurementException.class);
    }

    @Test
    void surface_apexBelowTheWallIsRejected() {
        JsonNode p = node("""
                {"unit":"CM","segments":[{"shape":"attic","mode":"sym","values":{"a":300,"b":200,"h":150}}],"openings":[]}""");
        assertThatThrownBy(() -> calc.compute(MeasurementType.SURFACE, p))
                .isInstanceOf(MeasurementException.class);
    }

    @Test
    void surface_cutCornerWithATopSideLongerThanTheBottomIsRejected() {
        JsonNode p = node("""
                {"unit":"CM","segments":[{"shape":"cut","mode":"d","values":{"a":50,"b":88,"c":60,"d":40}}],"openings":[]}""");
        assertThatThrownBy(() -> calc.compute(MeasurementType.SURFACE, p))
                .isInstanceOf(MeasurementException.class);
    }

    // ---- electrical -----------------------------------------------------------

    @Test
    void points_sumsTheCounts() {
        JsonNode p = node("""
                {"points":[{"type":"Розетка електрична","count":24,"heights":[300],"note":""},
                           {"type":"Вимикач 1 клавішний","count":9,"heights":[900],"note":""}]}""");
        assertThat(calc.compute(MeasurementType.ELECTRICAL_POINTS, p)).isEqualByComparingTo("33.000");
    }

    @Test
    void chase_busFromTop_busPlusDrop() {
        // Bus at 2600, a socket at h=300 → drop 2300 mm. Explicit bus 1200, both chased →
        // chase 1200 + 2300 = 3500 mm = 3.5 m (a chase is cut to size — no reserve).
        JsonNode p = node("""
                {"busLevel":2600,"busFromTop":true,"busLength":1200,"busChase":true,"reservePct":0,
                 "points":[{"kind":"socket","h":300,"qty":1,"chase":true}]}""");
        assertThat(calc.compute(MeasurementType.SHTROBA, p)).isEqualByComparingTo("3.500");
    }

    @Test
    void chase_busFromBottom_dropIsTheHeightItself() {
        // Bus along the floor (level 0) → the drop is simply the point's height. 900 + bus 1200.
        JsonNode p = node("""
                {"busLevel":2600,"busFromTop":false,"busLength":1200,"busChase":true,"reservePct":0,
                 "points":[{"kind":"switch","h":900,"qty":1,"chase":true}]}""");
        assertThat(calc.compute(MeasurementType.SHTROBA, p)).isEqualByComparingTo("2.100");
    }

    @Test
    void chase_excludesUnchasedBusAndDrops() {
        // Ceiling bus (busChase=false) + one drop chased, one not. Only the flagged drop counts.
        // chase = 0 (bus) + (2600−300)×1 (socket, chased) + 0 (outlet, not chased) = 2300 mm.
        JsonNode p = node("""
                {"busLevel":2600,"busFromTop":true,"busLength":1000,"busChase":false,"reservePct":0,
                 "points":[{"kind":"socket","h":300,"qty":1,"chase":true},
                           {"kind":"outlet","h":2600,"qty":1,"chase":false}]}""");
        assertThat(calc.compute(MeasurementType.SHTROBA, p)).isEqualByComparingTo("2.300");
    }

    @Test
    void cable_includesBusAllDropsAndReserve() {
        // Same input: cable reaches EVERY point regardless of chasing, + 10% reserve.
        // (1000 bus + 2300 socket + 0 A/C-drop) × 1.10 = 3630 mm = 3.63 m.
        JsonNode p = node("""
                {"busLevel":2600,"busFromTop":true,"busLength":1000,"busChase":false,"reservePct":10,
                 "points":[{"kind":"socket","h":300,"qty":1,"chase":true},
                           {"kind":"outlet","h":2600,"qty":1,"chase":false}]}""");
        assertThat(calc.compute(MeasurementType.CABLE, p)).isEqualByComparingTo("3.630");
    }

    @Test
    void chase_dropsMultipliedByQty() {
        // Bus 3000, both chased. Drops: (2600−300)×4 = 9200, (2600−900)×1 = 1700 → total 13900 mm.
        JsonNode p = node("""
                {"busLevel":2600,"busFromTop":true,"busLength":3000,"busChase":true,"reservePct":0,
                 "points":[{"kind":"socket","h":300,"qty":4,"chase":true},
                           {"kind":"switch","h":900,"qty":1,"chase":true}]}""");
        assertThat(calc.compute(MeasurementType.SHTROBA, p)).isEqualByComparingTo("13.900");
    }

    @Test
    void chase_pointAboveTheBusStillGivesAPositiveDrop() {
        // A/C outlet at 2600 with the bus at 2000 → |2000−2600| = 600, never negative.
        JsonNode p = node("""
                {"busLevel":2000,"busFromTop":true,"busLength":0,"busChase":true,"reservePct":0,
                 "points":[{"kind":"outlet","h":2600,"qty":1,"chase":true}]}""");
        assertThat(calc.compute(MeasurementType.SHTROBA, p)).isEqualByComparingTo("0.600");
    }

    @Test
    void surface_unknownShapeOrUnitIsRejected() {
        JsonNode shape = node("""
                {"unit":"M","segments":[{"shape":"hexagon","mode":"d","values":{"a":1}}],"openings":[]}""");
        assertThatThrownBy(() -> calc.compute(MeasurementType.SURFACE, shape))
                .isInstanceOf(MeasurementException.class);

        JsonNode unit = node("""
                {"unit":"FEET","segments":[{"shape":"rect","mode":"d","values":{"a":1,"b":1}}],"openings":[]}""");
        assertThatThrownBy(() -> calc.compute(MeasurementType.SURFACE, unit))
                .isInstanceOf(MeasurementException.class);
    }
}
