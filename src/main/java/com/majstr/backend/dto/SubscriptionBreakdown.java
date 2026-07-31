package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What the paid plans are actually made of — the answer to "how many of these PRO users are real?".
 *
 * <p><b>Why this exists.</b> The dashboard reported «Конверсія в платні» as
 * {@code (PRO + TEAM) / total}, which counts a five-day trial and an admin grant as revenue. That
 * number did not move on the day the first real payment arrived, and a metric that cannot tell you
 * that is not measuring the thing its label claims. A {@code SUCCESS} row in {@code payments} is
 * the only evidence money changed hands, so every figure here is keyed on it.</p>
 *
 * <p><b>Three states, and the order they are decided in matters.</b> A master on a non-FREE plan is
 * exactly one of:</p>
 * <ol>
 *   <li>{@code paid} — has at least one successful payment. Checked FIRST, because someone who
 *       started on the trial and then bought is a customer, not a trialist;</li>
 *   <li>{@code trial} — no payment, but {@code trialStartedAt} is set: they are inside the one-time
 *       5-day PRO trial;</li>
 *   <li>{@code granted} — no payment, no trial: an admin put them there (staff, a partner, a
 *       manual comp). Usually dateless, and deliberately never auto-downgraded.</li>
 * </ol>
 *
 * <p><b>{@code payingNow} vs {@code everPaid} are different questions</b> and both are shown. Ever-
 * paid is the honest conversion number and never goes down; paying-now is the one that reflects a
 * customer who did not renew. With a single figure you cannot see the difference between growth and
 * churn, which is precisely the distinction worth having on the day the first subscription lands.</p>
 *
 * @param payingNow        masters with a successful payment AND a paid plan still active
 * @param everPaid         masters with at least one successful payment, lapsed or not
 * @param onTrial          masters on a non-FREE plan through the trial and nothing else
 * @param granted          masters put on a paid plan by an admin
 * @param successfulPayments how many payments went through in total (renewals included)
 * @param revenueTotal     gross, in the account's currency — what the acquiring statement shows
 * @param revenue30d       the same over the last 30 days
 * @param recent           the newest successful payments, so "who paid" is answerable at a glance
 */
public record SubscriptionBreakdown(
        long payingNow,
        long everPaid,
        long onTrial,
        long granted,
        long successfulPayments,
        BigDecimal revenueTotal,
        BigDecimal revenue30d,
        List<RecentPayment> recent
) {
    /**
     * @param kind CHECKOUT (the master paid on the hosted page) or AUTO_RENEW (a token charge) —
     *             the two look identical in the money and mean very different things about retention
     */
    public record RecentPayment(
            String email,
            String fullName,
            BigDecimal amount,
            String plan,
            String period,
            String kind,
            int days,
            Instant paidAt
    ) {}
}
