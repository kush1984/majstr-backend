package com.majstr.backend.service;

import com.majstr.backend.dto.ExpenseRequest;
import com.majstr.backend.dto.ExpenseResponse;
import com.majstr.backend.dto.ObjectEconomyResponse;
import com.majstr.backend.entity.ExpenseSource;
import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
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
import java.util.List;
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
                .source(req.source() != null ? req.source() : ExpenseSource.MANUAL)
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
        Project object = requireEconomy(objectId, ownerId);

        // Only the flagged estimates (the accepted deal) — split into works (earnings)
        // and materials (passthrough); never the sum of all drafts/variants.
        BigDecimal works = estimateRepository.sumWorksCounted(objectId);
        BigDecimal materials = estimateRepository.sumMaterialsCounted(objectId);
        BigDecimal received = estimateRepository.sumDepositsCounted(objectId); // deposits paid so far

        BigDecimal spentReceipts = expenseRepository.sumBySource(objectId, ExpenseSource.RECEIPT); // real material cost
        BigDecimal spentManual = expenseRepository.sumBySource(objectId, ExpenseSource.MANUAL);    // unforeseen

        // Materials pot: the deposit covers material purchases. Negative = out of pocket.
        BigDecimal cashBalance = received.subtract(spentReceipts);

        // Earnings = labour − unforeseen. Materials aren't earnings — but once the object is
        // CLOSED, whatever is left in the materials pot (positive or negative) settles into profit.
        boolean completed = object.getStatus() == ProjectStatus.COMPLETED;
        BigDecimal profit = works.subtract(spentManual)
                .add(completed ? cashBalance : BigDecimal.ZERO);

        return new ObjectEconomyResponse(works, materials, received, spentReceipts, spentManual, profit, cashBalance);
    }

    /** Plan gate (PRO+) THEN ownership — a FREE master is refused before any object read.
     *  Returns the owned object so the caller can read its status (e.g. COMPLETED). */
    private Project requireEconomy(UUID objectId, UUID ownerId) {
        User user = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + ownerId));
        featureGuard.requireFeature(user, Feature.OBJECT_ECONOMY);
        return projectService.loadOwned(objectId, ownerId); // existence + ownership (404 / 403)
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
