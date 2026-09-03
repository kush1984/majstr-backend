package com.majstr.backend.service;

import com.majstr.backend.config.RateLimitProperties;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dictation gets its OWN bucket, for the same reason the QR reads got theirs: the three passes
 * answer different questions, and a day spent photographing receipts must never be why a master
 * cannot dictate a position. Nothing else bounds this endpoint — it is ungated in cut 0 and
 * persists nothing, so there is no business counter behind it.
 */
class DictationRateLimiterTest {

    private RateLimitProperties props(int dictationPerHour, int scanPerHour) {
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
                new RateLimitProperties.QrScan(120),
                new RateLimitProperties.Dictation(dictationPerHour));
    }

    @Test
    void blocksAfterHourlyCap() {
        DictationRateLimiter limiter = new DictationRateLimiter(props(2, 30));
        UUID account = UUID.randomUUID();

        assertThat(limiter.tryConsume(account).allowed()).isTrue();
        assertThat(limiter.tryConsume(account).allowed()).isTrue();

        var third = limiter.tryConsume(account);
        assertThat(third.allowed()).isFalse();
        assertThat(third.retryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    void accountsAreIndependent() {
        DictationRateLimiter limiter = new DictationRateLimiter(props(1, 30));

        assertThat(limiter.tryConsume(UUID.randomUUID()).allowed()).isTrue();
        assertThat(limiter.tryConsume(UUID.randomUUID()).allowed()).isTrue();
    }

    @Test
    void exhaustingDictationLeavesTheReceiptBucketsUntouched() {
        RateLimitProperties props = props(1, 3);
        DictationRateLimiter dictation = new DictationRateLimiter(props);
        ReceiptScanRateLimiter scan = new ReceiptScanRateLimiter(props);
        QrScanRateLimiter qr = new QrScanRateLimiter(props);
        UUID account = UUID.randomUUID();

        assertThat(dictation.tryConsume(account).allowed()).isTrue();
        assertThat(dictation.tryConsume(account).allowed()).isFalse(); // dictation budget spent…

        assertThat(scan.tryConsume(account).allowed()).isTrue();       // …reading a receipt still works
        assertThat(qr.tryConsume(account).allowed()).isTrue();
    }
}
