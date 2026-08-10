package com.majstr.backend.service;

import com.majstr.backend.dto.ExpenseRequest;
import com.majstr.backend.dto.ExpenseResponse;
import com.majstr.backend.dto.ObjectEconomyInternalsResponse;
import com.majstr.backend.dto.ObjectEconomyResponse;
import com.majstr.backend.dto.PaymentsSummaryResponse;
import com.majstr.backend.dto.SignedEstimatePanelResponse;
import com.majstr.backend.entity.ExpenseSource;
import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Object economy: the per-object expense journal + a real-profit summary (income from the
 * object's estimates minus its expenses), PLUS (payments-economy-portal iteration) the
 * FREE-visible per-estimate panels and payment schedule. See {@link ObjectEconomyResponse} for
 * exactly which half of {@code economy()}'s response is gated and which isn't — everything
 * ELSE here (the expense journal itself: add/list/update/delete) stays <b>PRO-gated</b>
 * ({@code Feature.OBJECT_ECONOMY} → PRO/TEAM; a FREE master gets 403 {@code UPGRADE_REQUIRED})
 * and <b>owner-scoped</b> (the object must belong to the caller). A PRO→FREE downgrade never
 * deletes expenses — the data survives, only the gate closes (server 403) until PRO returns.
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
    private final PaymentService paymentService;

    @Transactional
    public ExpenseResponse add(UUID objectId, UUID ownerId, ExpenseRequest req) {
        return add(objectId, ownerId, req, null);
    }

    /**
     * Add an expense, optionally with a CLIENT-PROVIDED id (offline authoring) so a replayed
     * add returns the existing row instead of duplicating it. Money must never double-count:
     * a duplicated expense silently understates the object's profit.
     *
     * <p>An id already belonging to a DIFFERENT object is rejected, never re-homed.
     */
    @Transactional
    public ExpenseResponse add(UUID objectId, UUID ownerId, ExpenseRequest req, UUID requestedId) {
        requireEconomy(objectId, ownerId);
        if (requestedId != null) {
            var existing = expenseRepository.findById(requestedId);
            if (existing.isPresent()) {
                if (!existing.get().getObjectId().equals(objectId)) {
                    throw new AccessDeniedException("Expense belongs to a different object");
                }
                return ExpenseResponse.from(existing.get()); // idempotent replay
            }
        }
        ObjectExpense expense = ObjectExpense.builder()
                .id(requestedId)
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

    /** Idempotent: a replayed offline delete of an already-gone expense is a no-op, not a 404. */
    @Transactional
    public void delete(UUID objectId, UUID expenseId, UUID ownerId) {
        requireEconomy(objectId, ownerId);
        expenseRepository.findByIdAndObjectId(expenseId, objectId).ifPresent(expenseRepository::delete);
    }

    /**
     * The economy tab's data. Unlike the expense journal above, this endpoint is reachable on
     * EVERY plan — only {@link ObjectEconomyResponse#estimates()} (the signed acts themselves) is
     * unconditional. {@link ObjectEconomyResponse#payments()} and {@link
     * ObjectEconomyResponse#internals()} are BOTH a soft null for FREE (economy-polish iteration —
     * previously only internals was gated; a FREE master now sees his signed deals and nothing
     * past them, matching the PWA's single PRO-lock teaser covering summary+payments+internals as
     * one block). Ownership is still required regardless of plan.
     */
    @Transactional(readOnly = true)
    public ObjectEconomyResponse economy(UUID objectId, UUID ownerId) {
        User user = loadUser(ownerId);
        projectService.loadOwned(objectId, ownerId); // existence + ownership (404 / 403)
        List<SignedEstimatePanelResponse> panels = signedEstimatePanels(objectId);
        boolean enabled = featureGuard.isEnabled(user, Feature.OBJECT_ECONOMY);
        PaymentsSummaryResponse payments = enabled ? paymentService.summaryUnchecked(objectId) : null;
        ObjectEconomyInternalsResponse internals = enabled
                ? internalsOf(objectId, payments.contractedTotal())
                : null;
        return new ObjectEconomyResponse(panels, payments, internals);
    }

    private ObjectEconomyInternalsResponse internalsOf(UUID objectId, BigDecimal contracted) {
        BigDecimal expenses = expenseRepository.sumAll(objectId);
        BigDecimal profit = contracted.subtract(expenses);
        return new ObjectEconomyInternalsResponse(expenses, profit);
    }

    private List<SignedEstimatePanelResponse> signedEstimatePanels(UUID objectId) {
        List<SignedEstimatePanelResponse> panels = new ArrayList<>();
        for (Object[] row : estimateRepository.findSignedEstimateSummaries(objectId)) {
            UUID id = (UUID) row[0];
            String name = (String) row[1];
            boolean counted = Boolean.TRUE.equals(row[2]);
            Instant signedAt = toInstant(row[3]);
            BigDecimal works = toBigDecimal(row[4]);
            BigDecimal materials = toBigDecimal(row[5]);
            BigDecimal markup = toBigDecimal(row[6]);
            BigDecimal discount = toBigDecimal(row[7]);
            panels.add(new SignedEstimatePanelResponse(
                    id, name, works, materials, markup, discount, works.add(materials), counted, signedAt));
        }
        return panels;
    }

    /** Native SUM can come back as BigDecimal (Postgres numeric); stay robust to other numerics. */
    private static BigDecimal toBigDecimal(Object value) {
        return value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString());
    }

    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        throw new IllegalStateException("Unexpected timestamp type: " + value.getClass());
    }

    private User loadUser(UUID ownerId) {
        return userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + ownerId));
    }

    /** Plan gate (PRO+) THEN ownership — a FREE master is refused before any object read.
     *  Only used by the expense-journal endpoints; {@link #economy} gates just its internals. */
    private Project requireEconomy(UUID objectId, UUID ownerId) {
        User user = loadUser(ownerId);
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
