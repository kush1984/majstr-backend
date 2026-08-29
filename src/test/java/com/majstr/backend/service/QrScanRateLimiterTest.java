package com.majstr.backend.service;

import com.majstr.backend.config.RateLimitProperties;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The QR reads have their OWN bucket (receipts-batch, master decision «рахуємо кюар шлях окремо і
 * не міняємо ліміти»): one gallery batch fires a QR read per photo, and sharing the recognition
 * bucket would let a batch of receipts eat the budget for the pass that actually spends a model
 * call. So a QR read must never touch {@link ReceiptScanRateLimiter}'s counter, and vice versa.
 */
class QrScanRateLimiterTest {

    private RateLimitProperties props(int qrPerHour, int scanPerHour) {
        return new RateLimitProperties(
                new RateLimitProperties.Login(5, 15),
                new RateLimitProperties.Register(5, 60),
                new RateLimitProperties.Forgot(5, 60),
                new RateLimitProperties.Portal(30, 1),
                new RateLimitProperties.Verification(60),
                new RateLimitProperties.EstimateEmail(10),
                new RateLimitProperties.MessageLink(5, 10),
                new RateLimitProperties.Question(5, 10),
                new RateLimitProperties.ReceiptScan(scanPerHour),
                new RateLimitProperties.QrScan(qrPerHour));
    }

    @Test
    void blocksAfterHourlyCap() {
        QrScanRateLimiter limiter = new QrScanRateLimiter(props(2, 30));
        UUID account = UUID.randomUUID();

        assertThat(limiter.tryConsume(account).allowed()).isTrue();
        assertThat(limiter.tryConsume(account).allowed()).isTrue();

        var third = limiter.tryConsume(account);
        assertThat(third.allowed()).isFalse();
        assertThat(third.retryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    void accountsAreIndependent() {
        QrScanRateLimiter limiter = new QrScanRateLimiter(props(1, 30));

        assertThat(limiter.tryConsume(UUID.randomUUID()).allowed()).isTrue();
        assertThat(limiter.tryConsume(UUID.randomUUID()).allowed()).isTrue();
    }

    @Test
    void exhaustingTheQrBucketLeavesTheRecognitionBucketUntouched() {
        RateLimitProperties props = props(1, 3);
        QrScanRateLimiter qr = new QrScanRateLimiter(props);
        ReceiptScanRateLimiter scan = new ReceiptScanRateLimiter(props);
        UUID account = UUID.randomUUID();

        assertThat(qr.tryConsume(account).allowed()).isTrue();
        assertThat(qr.tryConsume(account).allowed()).isFalse(); // QR budget spent…

        assertThat(scan.tryConsume(account).allowed()).isTrue(); // …the model pass is still free
    }
}
