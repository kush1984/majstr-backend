package com.majstr.backend.dto;

import com.majstr.backend.entity.PaymentReceipt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PaymentReceiptResponse(
        UUID id,
        UUID planPaymentId,
        /** Raw own label — set only for an unplanned receipt, null otherwise. */
        String label,
        /** Resolved name to show: {@code label} if set, else the linked plan stage's purpose,
         *  else a generic fallback (a plan stage deleted after this receipt was recorded). */
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
        return new PaymentReceiptResponse(r.getId(), planId, r.getLabel(), display, r.getAmount(), r.getReceivedAt(), r.isMaterialRefund());
    }
}
