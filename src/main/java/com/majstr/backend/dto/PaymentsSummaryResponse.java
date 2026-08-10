package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The owner's money summary for an object — "гроші", not "прибуток". {@code contractedTotal}
 * mirrors the same counted-estimates aggregation the economy panel uses, so the two numbers never
 * disagree; {@code received}/{@code remaining} come from {@code project_payment}, independent of
 * any estimate. <b>PRO only as of the economy-polish iteration</b> — {@code
 * ObjectExpenseService#economy} nulls this for FREE, same as {@code internals}. Distinct from the
 * PORTAL's own {@code PublicPortalView.PaymentsCard}: the portal computes its own {@code
 * contractedTotal} straight off {@code ProjectPaymentRepository}, from only the SHARED estimates
 * (see {@code PublicEstimateService.buildPaymentsCard}) — it never calls this type or {@link
 * com.majstr.backend.service.PaymentService}, so a FREE master's client-portal payments toggle
 * still works exactly as before; it just has nothing to show once mutations are gated (a FREE
 * master can no longer create a {@code project_payment} row at all).
 */
public record PaymentsSummaryResponse(
        BigDecimal contractedTotal,
        BigDecimal received,
        BigDecimal remaining,
        List<ProjectPaymentResponse> payments
) {}
