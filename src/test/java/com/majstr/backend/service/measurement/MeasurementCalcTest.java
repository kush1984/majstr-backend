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
}
