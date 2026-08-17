package com.majstr.backend.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ukrainian «сума прописом» — the boundary cases the prompt calls out (0, 1, 2, 5, 11, 14, 21, 100,
 * 101, 1000, 2000, 5000, 1 000 000) plus kopeck declension (0 / 01 / 50). These are where the
 * feminine-numeral and last-digit-declension rules earn their keep.
 */
class HryvniaInWordsTest {

    private static String words(String amount) {
        return HryvniaInWords.format(new BigDecimal(amount));
    }

    @Test
    void integerHryvniaDeclension() {
        assertThat(words("0.00")).isEqualTo("Нуль гривень 00 копійок");
        assertThat(words("1.00")).isEqualTo("Одна гривня 00 копійок");
        assertThat(words("2.00")).isEqualTo("Дві гривні 00 копійок");
        assertThat(words("5.00")).isEqualTo("П'ять гривень 00 копійок");
        assertThat(words("11.00")).isEqualTo("Одинадцять гривень 00 копійок");
        assertThat(words("14.00")).isEqualTo("Чотирнадцять гривень 00 копійок");
        assertThat(words("21.00")).isEqualTo("Двадцять одна гривня 00 копійок");
        assertThat(words("100.00")).isEqualTo("Сто гривень 00 копійок");
        assertThat(words("101.00")).isEqualTo("Сто одна гривня 00 копійок");
    }

    @Test
    void thousandsAndMillionsUseFeminineOrMasculineNumerals() {
        assertThat(words("1000.00")).isEqualTo("Одна тисяча гривень 00 копійок");
        assertThat(words("2000.00")).isEqualTo("Дві тисячі гривень 00 копійок");
        assertThat(words("5000.00")).isEqualTo("П'ять тисяч гривень 00 копійок");
        assertThat(words("1000000.00")).isEqualTo("Один мільйон гривень 00 копійок");
    }

    @Test
    void kopecksAreDigitsWithTheirOwnDeclension() {
        assertThat(words("0.50")).isEqualTo("Нуль гривень 50 копійок");
        assertThat(words("1.01")).isEqualTo("Одна гривня 01 копійка");
        assertThat(words("12.50")).isEqualTo("Дванадцять гривень 50 копійок");
    }

    @Test
    void aMixedRealisticAmount() {
        // The javadoc example, spelled end to end.
        assertThat(words("1500.05")).isEqualTo("Одна тисяча п'ятсот гривень 05 копійок");
        // Rounds to two places like every money figure in the app.
        assertThat(words("62300.00")).isEqualTo("Шістдесят дві тисячі триста гривень 00 копійок");
    }
}
