package com.majstr.backend.service.fiscal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payload shapes real РРО/ПРРО vendors print. This is the one place a bad read is cheap to
 * catch: everything downstream trusts these four fields, and the lookup answers 400 unless all four
 * match the stored receipt exactly.
 */
class FiscalQrPayloadTest {

    @Test
    void readsTheCabinetUrlShape() {
        Optional<FiscalQrPayload> parsed = FiscalQrPayload.parse(
                "https://cabinet.tax.gov.ua/cashregs/check?fn=4000123456&id=1234&date=20260815&time=143005&sm=1250.50&mac=ABCD");

        assertThat(parsed).isPresent();
        FiscalQrPayload qr = parsed.get();
        assertThat(qr.fn()).isEqualTo("4000123456");
        assertThat(qr.id()).isEqualTo("1234");
        assertThat(qr.sum()).isEqualByComparingTo("1250.50");
        assertThat(qr.issuedAt()).isEqualTo(LocalDateTime.of(2026, 8, 15, 14, 30, 5));
        assertThat(qr.lookupDate()).isEqualTo("2026-08-15 14:30:05");
        assertThat(qr.lookupSum()).isEqualTo("1250.50");
    }

    @Test
    void readsABareQueryStringAndDottedDate() {
        Optional<FiscalQrPayload> parsed =
                FiscalQrPayload.parse("fn=4000123456&id=7&date=15.08.2026&time=09:05&sm=99,90");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().issuedAt()).isEqualTo(LocalDateTime.of(2026, 8, 15, 9, 5));
        assertThat(parsed.get().sum()).isEqualByComparingTo("99.90");
    }

    @Test
    void readsADateThatCarriesItsOwnTime() {
        // Some codes print one combined value and no `time` parameter at all — glued, or spaced.
        assertThat(FiscalQrPayload.parse("fn=1&id=2&sm=10&date=20260815143005").orElseThrow().issuedAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 15, 14, 30, 5));
        assertThat(FiscalQrPayload.parse("?fn=1&id=2&sm=10&date=2026-08-15%2014:30:05").orElseThrow().issuedAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 15, 14, 30, 5));
    }

    @Test
    void aDatelessCodeStillDatesTheReceipt() {
        assertThat(FiscalQrPayload.parse("fn=1&id=2&sm=10&date=20260815").orElseThrow().issuedAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 15, 0, 0));
    }

    @Test
    void missingOrUnusableFieldsAreNotAFiscalCode() {
        // Each of these is a soft "not recognized" — the caller falls back to reading the photo,
        // it never surfaces as an error.
        assertThat(FiscalQrPayload.parse(null)).isEmpty();
        assertThat(FiscalQrPayload.parse("   ")).isEmpty();
        assertThat(FiscalQrPayload.parse("https://example.com/promo")).isEmpty();
        assertThat(FiscalQrPayload.parse("fn=1&id=2&date=20260815")).isEmpty();          // no sum
        assertThat(FiscalQrPayload.parse("fn=1&sm=10&date=20260815")).isEmpty();         // no id
        assertThat(FiscalQrPayload.parse("id=2&sm=10&date=20260815")).isEmpty();         // no fn
        assertThat(FiscalQrPayload.parse("fn=1&id=2&sm=10")).isEmpty();                  // no date
        assertThat(FiscalQrPayload.parse("fn=1&id=2&sm=0&date=20260815")).isEmpty();     // zero total
        assertThat(FiscalQrPayload.parse("fn=1&id=2&sm=abc&date=20260815")).isEmpty();
        assertThat(FiscalQrPayload.parse("fn=1&id=2&sm=10&date=notadate")).isEmpty();
    }

    @Test
    void parameterNamesAreCaseInsensitive() {
        Optional<FiscalQrPayload> parsed =
                FiscalQrPayload.parse("https://x/check?FN=9&ID=3&DATE=20260815&TIME=101010&SM=5.00");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().fn()).isEqualTo("9");
        assertThat(parsed.get().sum()).isEqualByComparingTo(new BigDecimal("5.00"));
    }
}
