package com.majstr.backend.dto;

import com.majstr.backend.entity.PaymentReceipt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PaymentReceiptResponse(
        UUID id,
        /** Set only on a receipt recorded against a stage of the removed payment plan. Still sent:
         *  an app build up to 1.68 decides by it whether a receipt's name and refund tick are its own. */
        UUID planPaymentId,
        /** Raw own label — null only on a receipt recorded against an old plan stage. */
        String label,
        /** Resolved name to show: {@code label} if set, else the old plan stage's purpose,
         *  else a generic fallback. */
        String displayLabel,
        BigDecimal amount,
        LocalDate receivedAt,
        /** «Повернення за матеріал»: the client handing back money the master laid out at the till,
         *  not payment for work (review B-65). Shown so the tick can be seen and corrected on the
         *  object's own screen — it decides how far the contract is really paid. */
        boolean materialRefund
) {
    public static PaymentReceiptResponse from(PaymentReceipt r) {
        String display = r.getLabel() != null ? r.getLabel()
                : r.getPlanPayment() != null ? r.getPlanPayment().getPurpose() : "Оплата";
        UUID planId = r.getPlanPayment() != null ? r.getPlanPayment().getId() : null;
        return new PaymentReceiptResponse(r.getId(), planId, r.getLabel(), display, r.getAmount(),
                r.getReceivedAt(), r.isMaterialRefund());
    }
}
