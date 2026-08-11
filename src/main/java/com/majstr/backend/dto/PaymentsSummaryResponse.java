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
        BigDecimal received,
        BigDecimal remaining,
        List<ProjectPaymentResponse> payments,
        /** Receipts with no matching plan stage ("Своє") — their own nodes on the timeline. */
        List<PaymentReceiptResponse> unplannedReceipts
) {}
