package com.majstr.backend.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptRecognizeResponseTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);
    private static final BigDecimal SUM = new BigDecimal("482.75");

    @Test
    void aVisionReadKeepsAPlausibleDate() {
        assertThat(ReceiptRecognizeResponse.read("Епіцентр", SUM, LocalDate.of(2026, 10, 30), TODAY).issuedAt())
                .isEqualTo(LocalDate.of(2026, 10, 30));
        // Exactly a year back is still a receipt someone kept in a drawer.
        assertThat(ReceiptRecognizeResponse.read("Епіцентр", SUM, TODAY.minusYears(1), TODAY).issuedAt())
                .isEqualTo(TODAY.minusYears(1));
    }

    @Test
    void aDateMoreThanAYearBackIsDropped_theRestOfTheReadStays() {
        ReceiptRecognizeResponse read = ReceiptRecognizeResponse.read("Епіцентр", SUM, LocalDate.of(2009, 6, 4), TODAY);

        assertThat(read.issuedAt()).isNull();
        assertThat(read.recognized()).isTrue();
        assertThat(read.label()).isEqualTo("Епіцентр");
        assertThat(read.amount()).isEqualByComparingTo(SUM);
    }

    @Test
    void aDateInTheFutureIsDropped() {
        assertThat(ReceiptRecognizeResponse.read("Епіцентр", SUM, TODAY.plusDays(2), TODAY).issuedAt()).isNull();
        // Tomorrow survives: a till across a time zone, a receipt photographed just after midnight.
        assertThat(ReceiptRecognizeResponse.read("Епіцентр", SUM, TODAY.plusDays(1), TODAY).issuedAt())
                .isEqualTo(TODAY.plusDays(1));
    }

    @Test
    void aQrDateIsTheTillsOwn_andIsKeptWhateverItSays() {
        assertThat(ReceiptRecognizeResponse.identified("Епіцентр", SUM, LocalDate.of(2009, 6, 4), "1", "2").issuedAt())
                .isEqualTo(LocalDate.of(2009, 6, 4));
    }
}
