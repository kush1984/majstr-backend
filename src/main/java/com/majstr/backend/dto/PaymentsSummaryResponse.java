package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The owner's money summary for an object — "гроші", not "прибуток". {@code contractedTotal}
 * mirrors the same counted-estimates aggregation the economy panel uses, so the two numbers never
 * disagree; {@code received}/{@code remaining} come from {@code payment_receipt} (V100), independent
 * of any estimate. <b>PRO only as of the economy-polish iteration</b> — {@code
 * ObjectExpenseService#economy} nulls this for FREE, same as {@code internals}. Distinct from the
 * PORTAL's own {@code PublicPortalView.PaymentsCard}, which computes its own numbers from only the
 * SHARED estimates (see {@code PublicEstimateService.buildPaymentsCard}) and never calls this type.
 */
public record PaymentsSummaryResponse(
        BigDecimal contractedTotal,
        /** Every hryvnia that arrived, refunds included — the itemized rows below add up to it. */
        BigDecimal received,
        /** What is still owed for the WORK: {@code max(0, contracted − workPaid)} (review B-65). */
        BigDecimal remaining,
        /** Σ of the receipts ticked «повернення за матеріал» — the gap between {@code received}
         *  and {@code workPaid}, named so it is explained rather than mysterious. */
        BigDecimal materialRefunds,
        /** The material money taken out of the work axis — all of {@code materialRefunds} since the
         *  object-money iteration (B-65 used to cap it at the receivable). */
        BigDecimal refundApplied,
        /** {@code received − refundApplied} — the only half «За договором» may be measured against. */
        BigDecimal workPaid,
        /** {@code max(0, workPaid − contracted)}. Shown rather than clamped away: the old
         *  {@code remaining} floored at zero and an overpayment simply disappeared. */
        BigDecimal overpaid,
        /** Every payment that arrived, oldest first — they add up to {@code received}. */
        List<PaymentReceiptResponse> receipts,
        /** Always empty — the payment plan is gone. Sent for an app build up to 1.68 (still cached
         *  on phones), whose payments block calls {@code summary.payments.forEach} and would crash
         *  on a missing list. Remove once no such build can be running. */
        List<Object> payments,
        /** {@link #receipts} again, under the name a build up to 1.68 lists — with no stages, every
         *  payment is its own row there, as it is now. Same lifetime as {@link #payments}. */
        List<PaymentReceiptResponse> unplannedReceipts
) {
    public PaymentsSummaryResponse(BigDecimal contractedTotal, BigDecimal received, BigDecimal remaining,
                                   BigDecimal materialRefunds, BigDecimal refundApplied, BigDecimal workPaid,
                                   BigDecimal overpaid, List<PaymentReceiptResponse> receipts) {
        this(contractedTotal, received, remaining, materialRefunds, refundApplied, workPaid, overpaid,
                receipts, List.of(), receipts);
    }
}
