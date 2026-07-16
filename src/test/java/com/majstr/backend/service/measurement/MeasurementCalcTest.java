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
