package com.majstr.backend.dto;

import com.majstr.backend.entity.ExpenseCategory;
import com.majstr.backend.entity.ExpenseSource;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Create/update an object expense. Only the amount is required (fast entry standing
 * in a hardware store); category defaults on the client, {@code spentAt} defaults to
 * today server-side when absent. {@code source} is RECEIPT only from the receipt-import
 * flow; a hand-entered expense leaves it null → MANUAL (unforeseen).
 */
public record ExpenseRequest(
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("100000000") BigDecimal amount,
        @NotNull ExpenseCategory category,
        @Size(max = 500) String note,
        LocalDate spentAt,
        ExpenseSource source
) {}
