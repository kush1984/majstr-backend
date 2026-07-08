package com.majstr.backend.dto;

import com.majstr.backend.entity.ExpenseCategory;
import com.majstr.backend.entity.ObjectExpense;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One expense in the object journal. Owner-only — never leaves the master's own API. */
public record ExpenseResponse(
        UUID id,
        BigDecimal amount,
        ExpenseCategory category,
        String note,
        LocalDate spentAt,
        Instant createdAt
) {
    public static ExpenseResponse from(ObjectExpense e) {
        return new ExpenseResponse(e.getId(), e.getAmount(), e.getCategory(), e.getNote(),
                e.getSpentAt(), e.getCreatedAt());
    }
}
