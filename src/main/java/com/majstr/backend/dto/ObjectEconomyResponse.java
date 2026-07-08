package com.majstr.backend.dto;

import com.majstr.backend.entity.ExpenseCategory;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The object-economy summary (PRO): what the object earns vs what it cost.
 * {@code incomeTotal} = sum of all its estimates except REJECTED; {@code incomeSigned}
 * = only signed ones (shown alongside for honesty). {@code profit = incomeTotal -
 * expensesTotal}; {@code profitSigned} uses the signed income. This DTO is owner-only —
 * it never appears in the client portal, PDF, or any share-token response.
 */
public record ObjectEconomyResponse(
        BigDecimal incomeTotal,
        BigDecimal incomeSigned,
        BigDecimal expensesTotal,
        Map<ExpenseCategory, BigDecimal> expensesByCategory,
        BigDecimal profit,
        BigDecimal profitSigned
) {}
