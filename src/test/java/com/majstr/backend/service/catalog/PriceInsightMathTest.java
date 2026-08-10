package com.majstr.backend.service.catalog;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PriceInsightMathTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void threeCleanValues_medianIsTheMiddleOne_noneTrimmed() {
        var stats = PriceInsightMath.trimmedMedian(List.of(bd("500"), bd("520"), bd("540")));

        assertThat(stats).isNotNull();
        assertThat(stats.count()).isEqualTo(3);
        assertThat(stats.median()).isEqualByComparingTo("520");
        assertThat(stats.min()).isEqualByComparingTo("500");
        assertThat(stats.max()).isEqualByComparingTo("540");
    }

    @Test
    void twoValues_belowTheMinimum_returnsNull() {
        // "положення з N=2 → у чергу НЕ потрапляє" — the caller's signal to drop the candidate.
        assertThat(PriceInsightMath.trimmedMedian(List.of(bd("500"), bd("520")))).isNull();
    }

    @Test
    void aWildOutlierAmongExactlyThree_theMedianItselfAlreadyIgnoresIt() {
        // At the smallest legal sample, IQR's own quartile interpolation is hostage to the
        // outlier it would need to exclude (Q3 ends up interpolated FROM the outlier), so
        // nothing gets trimmed here — but that is fine, because the PLAIN median of exactly
        // three values already picks the middle-ranked one regardless of how extreme either end
        // is. The outlier moves max (an honest, visible "розкид"), never the reported price.
        var stats = PriceInsightMath.trimmedMedian(List.of(bd("500"), bd("520"), bd("999999")));

        assertThat(stats).isNotNull();
        assertThat(stats.count()).isEqualTo(3);
        assertThat(stats.median()).isEqualByComparingTo("520");
        assertThat(stats.max()).isEqualByComparingTo("999999"); // shown, not hidden
    }

    @Test
    void anOutlierAmongFiveIsTrimmedOut_theOthersStillClearTheMinimum() {
        var stats = PriceInsightMath.trimmedMedian(
                List.of(bd("480"), bd("500"), bd("510"), bd("520"), bd("999999")));

        assertThat(stats).isNotNull();
        assertThat(stats.count()).isEqualTo(4); // the outlier dropped out
        assertThat(stats.max()).isEqualByComparingTo("520"); // never the outlier
        assertThat(stats.median()).isEqualByComparingTo("505.00");
    }

    @Test
    void allIdenticalValues_iqrIsZero_nothingWronglyExcluded() {
        var stats = PriceInsightMath.trimmedMedian(List.of(bd("300"), bd("300"), bd("300")));

        assertThat(stats).isNotNull();
        assertThat(stats.count()).isEqualTo(3);
        assertThat(stats.median()).isEqualByComparingTo("300");
    }

    @Test
    void median_ofAnEvenCount_averagesTheMiddleTwo() {
        assertThat(PriceInsightMath.median(List.of(bd("100"), bd("200"), bd("300"), bd("400"))))
                .isEqualByComparingTo("250");
    }

    @Test
    void median_ofASingleValue_isItself() {
        assertThat(PriceInsightMath.median(List.of(bd("777")))).isEqualByComparingTo("777");
    }

    @Test
    void emptyInput_returnsNull() {
        assertThat(PriceInsightMath.trimmedMedian(List.of())).isNull();
    }
}
