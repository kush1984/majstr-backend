package com.majstr.backend.dto;

import com.majstr.backend.entity.ProjectPayment;
import com.majstr.backend.entity.ProjectPaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ProjectPaymentResponse(
        UUID id,
        BigDecimal amount,
        LocalDate dueDate,
        String nextStage,
        String purpose,
        /** Σ of this stage's {@link PaymentReceiptResponse}s — replaces the old single paidAmount. */
        BigDecimal received,
        BigDecimal remaining,
        ProjectPaymentStatus status,
        int sortOrder,
        List<PaymentReceiptResponse> receipts
) {
    public static ProjectPaymentResponse from(ProjectPayment p, LocalDate today, BigDecimal received,
                                               List<PaymentReceiptResponse> receipts) {
        BigDecimal remaining = p.getAmount().subtract(received).max(BigDecimal.ZERO);
        return new ProjectPaymentResponse(p.getId(), p.getAmount(), p.getDueDate(), p.getNextStage(),
                p.getPurpose(), received, remaining, p.status(today, received), p.getSortOrder(), receipts);
    }
}
