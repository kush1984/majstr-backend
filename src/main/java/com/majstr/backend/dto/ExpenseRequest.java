package com.majstr.backend.dto;

import com.majstr.backend.entity.ExpenseCategory;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Create/update an object expense. Only the amount is required (fast entry standing
 * in a hardware store); category defaults on the client, {@code spentAt} defaults to
 * today server-side when absent. There is no {@code source}: a hand-entered expense is always
 * MANUAL, and RECEIPT is set only by the server when a receipt posts one (B-84 remainder — the
 * estimate import that used to send RECEIPT no longer offers to save an expense).
 */
public record ExpenseRequest(
        @NotNull @DecimalMin("0.01") @DecimalMax("100000000")
        @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @NotNull ExpenseCategory category,
        @Size(max = 500) String note,
        LocalDate spentAt
) {}
