package com.majstr.backend.service;

import com.majstr.backend.exception.AiExtractionException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the model's answer turns into before {@link PaymentReceiptCheck} sees it (V217). */
class PaymentReceiptReaderTest {

    private final PaymentReceiptReader reader = new PaymentReceiptReader(null, new ObjectMapper());

    @Test
    void readsTheFields_normalisingIbanAndDate() {
        var read = reader.parse("""
                {"amount":"10 000,50","currency":"UAH","status":"success","date":"19.11.2026",
                 "recipientIban":"ua21 3223 1300 0002 6007 2335 6600 1","recipientCardLast4":"1234",
                 "recipientName":"Іван П."}""");

        assertThat(read.amount()).isEqualByComparingTo("10000.50");
        assertThat(read.status()).isEqualTo(PaymentReceiptReader.Status.SUCCESS);
        assertThat(read.date()).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(read.recipientIban()).isEqualTo("UA213223130000026007233566001");
        assertThat(read.recipientCardLast4()).isEqualTo("1234");
    }

    @Test
    void theSentinelsBecomeNulls_andAMaskedLastFourIsNotGuessed() {
        var read = reader.parse("""
                {"amount":0,"currency":"","status":"maybe","date":"","recipientIban":"",
                 "recipientCardLast4":"**34","recipientName":""}""");

        assertThat(read.amount()).isNull();
        assertThat(read.currency()).isNull();
        assertThat(read.status()).isEqualTo(PaymentReceiptReader.Status.UNKNOWN);
        assertThat(read.date()).isNull();
        assertThat(read.recipientIban()).isNull();
        assertThat(read.recipientCardLast4()).isNull();
    }

    @Test
    void anUnusableAnswerIsAnExtractionFailure() {
        assertThatThrownBy(() -> reader.parse("not json")).isInstanceOf(AiExtractionException.class);
    }

    @Test
    void aFileThatIsNeitherPictureNorPdfIsNotSentAnywhere() {
        assertThatThrownBy(() -> reader.read("text/plain", new byte[] {1}))
                .isInstanceOf(AiExtractionException.class);
    }
}
