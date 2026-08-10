package com.majstr.backend.dto;

import com.majstr.backend.entity.ProjectPayment;
import com.majstr.backend.entity.ProjectPaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ProjectPaymentResponse(
        UUID id,
        BigDecimal amount,
        LocalDate dueDate,
        String nextStage,
        String purpose,
        BigDecimal paidAmount,
        Instant paidAt,
        ProjectPaymentStatus status,
        int sortOrder
) {
    public static ProjectPaymentResponse from(ProjectPayment p, LocalDate today) {
        return new ProjectPaymentResponse(p.getId(), p.getAmount(), p.getDueDate(), p.getNextStage(),
                p.getPurpose(), p.getPaidAmount(), p.getPaidAt(), p.status(today), p.getSortOrder());
    }
}
