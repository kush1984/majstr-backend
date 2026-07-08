package com.majstr.backend.service;

import com.majstr.backend.dto.ExpenseRequest;
import com.majstr.backend.dto.ExpenseResponse;
import com.majstr.backend.dto.ObjectEconomyResponse;
import com.majstr.backend.entity.ExpenseCategory;
import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Object economy (PRO): the per-object expense journal + a real-profit summary
 * (income from the object's estimates minus its expenses). Every entry point is
 * <b>plan-gated</b> ({@code Feature.OBJECT_ECONOMY} → PRO/TEAM; a FREE master gets 403
 * {@code UPGRADE_REQUIRED}) and <b>owner-scoped</b> (the object must belong to the
 * caller). A PRO→FREE downgrade never deletes expenses — the data survives, only the
 * gate closes (server 403) until PRO returns.
 *
 * <p>Nothing here is ever exposed to a client: the economy DTOs are owner-only and are
 * not part of any estimate/portal/PDF/share response.</p>
 */
@Service
@RequiredArgsConstructor
public class ObjectExpenseService {

    private final ObjectExpenseRepository expenseRepository;
    private final EstimateRepository estimateRepository;
    private final ProjectService projectService;
    private final UserRepository userRepository;
    private final FeatureGuard featureGuard;

    @Transactional
    public ExpenseResponse add(UUID objectId, UUID ownerId, ExpenseRequest req) {
        requireEconomy(objectId, ownerId);
        ObjectExpense expense = ObjectExpense.builder()
                .objectId(objectId)
                .amount(req.amount())
                .category(req.category())
                .note(trimToNull(req.note()))
                .spentAt(req.spentAt() != null ? req.spentAt() : LocalDate.now())
                .build();
        return ExpenseResponse.from(expenseRepository.save(expense));
    }

    @Transactional(readOnly = true)
    public List<ExpenseResponse> list(UUID objectId, UUID ownerId) {
        requireEconomy(objectId, ownerId);
        return expenseRepository.findByObjectIdOrderBySpentAtDescCreatedAtDesc(objectId).stream()
                .map(ExpenseResponse::from)
                .toList();
    }

    @Transactional
    public ExpenseResponse update(UUID objectId, UUID expenseId, UUID ownerId, ExpenseRequest req) {
        requireEconomy(objectId, ownerId);
        ObjectExpense expense = loadExpense(objectId, expenseId);
        expense.setAmount(req.amount());
        expense.setCategory(req.category());
        expense.setNote(trimToNull(req.note()));
        if (req.spentAt() != null) {
            expense.setSpentAt(req.spentAt());
        }
        return ExpenseResponse.from(expense);
    }

    @Transactional
    public void delete(UUID objectId, UUID expenseId, UUID ownerId) {
        requireEconomy(objectId, ownerId);
        expenseRepository.delete(loadExpense(objectId, expenseId));
    }

    @Transactional(readOnly = true)
    public ObjectEconomyResponse economy(UUID objectId, UUID ownerId) {
        requireEconomy(objectId, ownerId);

        BigDecimal incomeTotal = estimateRepository.sumIncomeExcludingRejected(objectId);
        BigDecimal incomeSigned = estimateRepository.sumIncomeSigned(objectId);

        Map<ExpenseCategory, BigDecimal> byCategory = new EnumMap<>(ExpenseCategory.class);
        for (ObjectExpenseRepository.CategoryTotal row : expenseRepository.sumByCategory(objectId)) {
            byCategory.put(row.getCategory(), row.getTotal());
        }
        BigDecimal expensesTotal = byCategory.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ObjectEconomyResponse(
                incomeTotal,
                incomeSigned,
                expensesTotal,
                byCategory,
                incomeTotal.subtract(expensesTotal),
                incomeSigned.subtract(expensesTotal));
    }

    /** Plan gate (PRO+) THEN ownership — a FREE master is refused before any object read. */
    private void requireEconomy(UUID objectId, UUID ownerId) {
        User user = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + ownerId));
        featureGuard.requireFeature(user, Feature.OBJECT_ECONOMY);
        projectService.loadOwned(objectId, ownerId); // existence + ownership (404 / 403)
    }

    private ObjectExpense loadExpense(UUID objectId, UUID expenseId) {
        return expenseRepository.findByIdAndObjectId(expenseId, objectId)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + expenseId));
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
