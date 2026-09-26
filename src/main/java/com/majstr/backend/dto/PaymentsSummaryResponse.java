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
        /** How much of that refund a till receivable actually absorbed: {@code min(refunds,
         *  reimbursable)}. Anything above it had no material left to pay off and stays work money. */
        BigDecimal refundApplied,
        /** {@code received − refundApplied} — the only half «За договором» may be measured against. */
        BigDecimal workPaid,
        /** {@code max(0, workPaid − contracted)}. Shown rather than clamped away: the old
         *  {@code remaining} floored at zero and an overpayment simply disappeared. */
        BigDecimal overpaid,
        List<ProjectPaymentResponse> payments,
        /** Receipts with no matching plan stage ("Своє") — their own nodes on the timeline. */
        List<PaymentReceiptResponse> unplannedReceipts
) {}
