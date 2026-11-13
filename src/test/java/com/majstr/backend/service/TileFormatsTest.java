package com.majstr.backend.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TileFormatsTest {

    private static final TileFormats.Format F300X600 = new TileFormats.Format(300, 600);

    @Test
    void readsTheFormatHoweverTheCrossIsWritten() {
        for (String name : new String[] {"Укладання плитки 300х600", "плитка 600x300", "Плитка 300 × 600 мм",
                "плитка 300*600", "Укладання плитки 600Х300"}) {
            assertThat(TileFormats.fromName(name)).as(name).contains(F300X600);
        }
    }

    @Test
    void aNameWithNoFormatSaysNothingAgainstTheLayout() {
        assertThat(TileFormats.fromName("Укладання плитки кабанчик")).isEmpty();
        assertThat(TileFormats.nameAgrees("Облицювання фартуха", F300X600)).isTrue();
    }

    @Test
    void anotherFormatDisagreesAndCentimetresAgree() {
        assertThat(TileFormats.nameAgrees("Укладання плитки 600х600", F300X600)).isFalse();
        assertThat(TileFormats.nameAgrees("Плитка 30х60", F300X600)).isTrue();
        assertThat(TileFormats.nameAgrees("Укладання плитки 1200х200 ялинкою", F300X600)).isFalse();
    }

    @Test
    void aLayingLineInTheMastersOwnWordsStandsForTheLibraryPositionOfItsFormat() {
        assertThat(TileFormats.libraryKeysFor("Укладання плитки на підлогу 60×60"))
                .containsExactly("укладання плитки 600х600", "укладання плитки 600х600 мм");
        assertThat(TileFormats.libraryKeysFor("Укладання плитки на стіни 30×60")).first().isEqualTo("укладання плитки 300х600");
        assertThat(TileFormats.libraryKeysFor("Укладання великоформатної плитки 120×60")).first().isEqualTo("укладання плитки 600х1200");
        assertThat(TileFormats.libraryKeysFor("Облицювання стін керамогранітом 1200x1200")).last().isEqualTo("укладання плитки 1200х1200 мм");
    }

    @Test
    void anythingUnclearStandsForNothing() {
        for (String name : new String[] {
                "Укладання плитки на підлогу (до 60×60)", // a range of formats
                "Укладання плитки 30×60 і 60×60",         // two formats
                "Укладання плитки на підлогу",             // no format
                "Демонтаж керамічної плитки 60×60",        // not laying
                "Запил плитки під 45° 60×60",              // a cut
                "Укладання тротуарної плитки 30×30",       // paving
                "Облицювання фасаду клінкерною плиткою 24×7", // clinker
                "Укладання мозаїки 30×30",                 // mosaic
                "Фарбування стін 2×3"}) {                  // not tile at all
            assertThat(TileFormats.libraryKeysFor(name)).as(name).isEmpty();
        }
    }

    @Test
    void theCustomLabelIsShortSideFirst() {
        assertThat(TileFormats.Format.of(500, 300).label()).isEqualTo("300×500 мм");
    }
}
