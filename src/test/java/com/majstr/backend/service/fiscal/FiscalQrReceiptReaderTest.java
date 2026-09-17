package com.majstr.backend.service.fiscal;

import com.majstr.backend.dto.ReceiptRecognizeResponse;
import com.majstr.backend.service.importer.EstimateExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one QR read both receipt tables use (review item B-04).
 *
 * <p>It exists because the two callers had drifted in the way that quietly kills a feature: the
 * object's «Чеки» returned the printed identity, the act's «Чеки та рахунки» threw it away. So an
 * act receipt could never carry {@code fn}/{@code id}, and the cross-table duplicate check had
 * nothing to compare on one whole side of every pair — including the pair that bills a client
 * twice. These tests moved here from {@code WorkActReceiptRecognitionTest} with the logic.</p>
 */
@ExtendWith(MockitoExtension.class)
class FiscalQrReceiptReaderTest {

    /** A real payload shape: the identity is `fn` + `id`, and the total is what the paper says. */
    private static final String QR = "fn=4000123456&id=17&date=20260815&time=143005&sm=690.00";

    @Mock private FiscalQrService fiscalQr;

    @InjectMocks private FiscalQrReceiptReader reader;

    @Test
    void readsTheFooterAndThePrintedIdentity_withNoModelAndNoLookup() {
        // The stub is on read(QR, false) EXACTLY — a call with `true` would find no stub and answer
        // null. The ДПС lookup only ever added the seller name and the positions, and neither
        // surface carries positions any more, so this fires automatically on every photo of a batch
        // and must never wait on a third party.
        when(fiscalQr.read(QR, false)).thenReturn(Optional.of(fiscalReceipt()));

        ReceiptRecognizeResponse read = reader.read(QR);

        assertThat(read.recognized()).isTrue();
        assertThat(read.label()).isEqualTo("Епіцентр");
        assertThat(read.amount()).isEqualByComparingTo("690.00");
        assertThat(read.issuedAt()).isEqualTo(LocalDate.of(2026, 8, 15));
        // The half that was missing on the act side, and the whole point of B-04.
        assertThat(read.fiscalFn()).isEqualTo("4000123456");
        assertThat(read.fiscalId()).isEqualTo("17");
        verify(fiscalQr).read(QR, false);
    }

    /**
     * The identity comes from the PAYLOAD, never from what the lookup answered. {@code
     * FiscalQrService.read} is a ladder that degrades rather than fails — an unreachable tax service
     * still yields a recognized receipt off total + date — and the identity is printed on the paper
     * either way. Reading it from the lookup would make it vanish on exactly the bad day the master
     * is standing in a basement.
     */
    @Test
    void theIdentityIsTheOneOnThePaper_evenWhenTheLookupAnsweredLittle() {
        when(fiscalQr.read(QR, false)).thenReturn(Optional.of(
                new FiscalReceipt(null, LocalDate.of(2026, 8, 15), new BigDecimal("690.00"), List.of())));

        ReceiptRecognizeResponse read = reader.read(QR);

        assertThat(read.label()).isNull();
        assertThat(read.fiscalFn()).isEqualTo("4000123456");
        assertThat(read.fiscalId()).isEqualTo("17");
    }

    /** Soft, so the dialog can fall back to the photo — never an error the master has to dismiss. */
    @Test
    void anUnreadableCodeIsSoft() {
        when(fiscalQr.read(QR, false)).thenReturn(Optional.empty());

        ReceiptRecognizeResponse read = reader.read(QR);

        assertThat(read.recognized()).isFalse();
        assertThat(read.fiscalFn()).isNull();
        assertThat(read.fiscalId()).isNull();
    }

    private static FiscalReceipt fiscalReceipt() {
        return new FiscalReceipt("Епіцентр", LocalDate.of(2026, 8, 15), new BigDecimal("690.00"),
                List.of(new EstimateExtractor.Extracted.Line(
                        "Шпаклівка", "шт", new BigDecimal("2"), new BigDecimal("345"), "MATERIAL", null)));
    }
}
