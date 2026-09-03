package com.majstr.backend.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        Login login,
        Register register,
        Forgot forgot,
        Portal portal,
        Verification verification,
        EstimateEmail estimateEmail,
        MessageLink messageLink,
        Question question,
        ReceiptScan receiptScan,
        QrScan qrScan,
        Dictation dictation
) {
    public record Login(
            @Positive int maxAttempts,
            @Positive int windowMinutes
    ) {}

    /** Cap on account registrations per client IP — curbs mass signups and verification-email spam. */
    public record Register(
            @Positive int maxAttempts,
            @Positive int windowMinutes
    ) {}

    /** Cap on password-reset requests per client IP+email — curbs reset-email spam. */
    public record Forgot(
            @Positive int maxAttempts,
            @Positive int windowMinutes
    ) {}

    public record Portal(
            @Positive int maxAttempts,
            @Positive int windowMinutes
    ) {}

    /** Cooldown between verification-email resends, per user. */
    public record Verification(
            @Positive int cooldownSeconds
    ) {}

    /** Cap on estimate-share emails per account per hour. */
    public record EstimateEmail(
            @Positive int maxPerHour
    ) {}

    /**
     * Cap on messages sent through a master's message link, per IP AND per link.
     *
     * <p>Tighter than the blanket 30/min on /api/public/**, because this one WRITES — and, from the
     * next step, writes files. Keyed on the pair so one address cannot spray every link a master has,
     * and one leaked link cannot be filled from a hundred addresses either.</p>
     */
    public record MessageLink(
            @Positive int maxAttempts,
            @Positive int windowMinutes
    ) {}

    /**
     * Cap on client questions through ANY public portal (legacy/signature/economy/act), per IP AND
     * per token — same reasoning as {@link MessageLink}: a question WRITES (stored message + push to
     * the master's phone), so it must be tighter than the blanket read limit.
     */
    public record Question(
            @Positive int maxAttempts,
            @Positive int windowMinutes
    ) {}

    /**
     * Cap on act-receipt recognition calls per account per hour. The only LLM flow a FREE plan can
     * reach (its meta pass), and it persists nothing, so nothing else bounds how often it is spent.
     */
    public record ReceiptScan(
            @Positive int maxPerHour
    ) {}

    /**
     * Cap on fiscal-QR reads per account per hour, counted SEPARATELY from {@link ReceiptScan}
     * (master decision, 2026-08-24). A QR read spends no model call — it is the free first rung of
     * «додати чек з фото», and a batch of photos spends one per receipt. Sharing the recognition
     * bucket would let one photo batch eat the budget for the pass that actually costs money.
     */
    public record QrScan(
            @Positive int maxPerHour
    ) {}

    /**
     * Cap on dictated-position parses per account per hour. Same reasoning as {@link ReceiptScan}
     * — a model call that persists nothing, reachable on FREE — but its own bucket, so a day spent
     * photographing receipts is never why dictation stops working.
     */
    public record Dictation(
            @Positive int maxPerHour
    ) {}
}
