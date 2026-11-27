package com.majstr.backend.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FenceSidesTest {

    private static final BigDecimal PANEL = new BigDecimal("2.5");

    @Test
    void everySideStartsAFreshPanelAndEveryRunAddsAnEndPost() {
        FenceSides sides = FenceSides.parse("25 + 15 + 25; 10").orElseThrow();

        assertThat(sides.pieces(PANEL)).isEqualTo(10 + 6 + 10 + 4);
        assertThat(sides.runCount()).isEqualTo(2);
        assertThat(sides.total()).isEqualByComparingTo("75");
    }

    /** 12,6 + 12,6 is 25,2 m — 11 panels as one figure, 12 as two sides that each end in a cut one. */
    @Test
    void aSideNotAMultipleOfThePanelEndsInACutPanel() {
        assertThat(FenceSides.parse("12,6 + 12,6").orElseThrow().pieces(PANEL)).isEqualTo(12);
        assertThat(FenceSides.parse("5").orElseThrow().pieces(PANEL)).isEqualTo(2);
    }

    @Test
    void separatorsAreAPlusASpaceOrACommaWithASpaceAndADecimalCommaIsKept() {
        assertThat(FenceSides.parse("25, 15, 25; 10").orElseThrow().canonical()).isEqualTo("25 + 15 + 25; 10");
        assertThat(FenceSides.parse("25 15 25;10").orElseThrow().canonical()).isEqualTo("25 + 15 + 25; 10");
        assertThat(FenceSides.parse("12,5+7.5").orElseThrow().canonical()).isEqualTo("12,5 + 7,5");
        assertThat(FenceSides.parse(" 25 ;; 10 ; ").orElseThrow().runCount()).isEqualTo(2);
    }

    @Test
    void anythingThatIsNotAListOfLengthsIsRefused() {
        assertThat(FenceSides.parse("")).isEmpty();
        assertThat(FenceSides.parse("двадцять")).isEmpty();
        assertThat(FenceSides.parse("25 + 0")).isEmpty();
        assertThat(FenceSides.parse("25 + -3")).isEmpty();
        assertThat(FenceSides.parse("1001")).isEmpty();
        assertThat(FenceSides.parse("1 ".repeat(51))).isEmpty();
        assertThat(FenceSides.parse(";")).isEmpty();
    }
}
