package com.majstr.backend.service;

import com.majstr.backend.dto.CrewMarginResponse;
import com.majstr.backend.dto.ExpenseRequest;
import com.majstr.backend.dto.ExpenseResponse;
import com.majstr.backend.dto.ObjectEconomyActsResponse;
import com.majstr.backend.dto.ObjectEconomyInternalsResponse;
import com.majstr.backend.dto.ObjectEconomyMaterialsResponse;
import com.majstr.backend.dto.ObjectEconomyResponse;
import com.majstr.backend.dto.PaymentsSummaryResponse;
import com.majstr.backend.dto.SignedEstimatePanelResponse;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateKind;
import com.majstr.backend.entity.ExpenseSource;
import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ExpenseLinkedToReceiptException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.PaymentReceiptRepository;
import com.majstr.backend.repository.ProjectReceiptRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.WorkActItemRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final EstimateItemRepository estimateItemRepository;
    private final ProjectService projectService;
    private final UserRepository userRepository;
    private final FeatureGuard featureGuard;
    private final PaymentService paymentService;
    private final WorkActItemRepository workActItemRepository;
    private final WorkActReceiptRepository workActReceiptRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final ProjectReceiptRepository projectReceiptRepository;

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
        requireNotOwnedByAReceipt(expense.getId());
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
        expenseRepository.findByIdAndObjectId(expenseId, objectId).ifPresent(expense -> {
            requireNotOwnedByAReceipt(expense.getId());
            expenseRepository.delete(expense);
        });
    }

    /**
     * An expense an object receipt created belongs to that receipt: the two are one fact, and the
     * receipt mirrors its amount, label and date onto the row (V129). Editing or deleting it here
     * would leave the receipt saying «це моя витрата» over a figure the journal no longer agrees
     * with — silently, and in the one place the master reads his profit. He is sent to the receipt,
     * where the same two taps also turn the whole claim off.
     *
     * <p>The test is the BACK-LINK, never {@code source = RECEIPT}: an act's receipts are posted as
     * MATERIALS/RECEIPT rows too ({@code ActAddendumCreator.postReceiptExpenses}) and no receipt
     * owns those — they stay editable.</p>
     */
    private void requireNotOwnedByAReceipt(UUID expenseId) {
        if (projectReceiptRepository.existsByExpenseId(expenseId)) {
            throw new ExpenseLinkedToReceiptException();
        }
    }

    /**
     * The economy tab's data. This endpoint is reachable on EVERY plan — only {@link
     * ObjectEconomyResponse#estimates()} (the signed acts themselves) was ever unconditional.
     * {@link ObjectEconomyResponse#payments()} and {@link ObjectEconomyResponse#internals()} are
     * BOTH gated by one soft check (economy-polish iteration — previously only internals was
     * gated), currently {@code true} for every plan including FREE: {@code Feature.OBJECT_ECONOMY}
     * is TEMPORARILY granted to FREE too, see the comment on {@code Plan.FREE} in {@link
     * com.majstr.backend.feature.PlanConfig}. Ownership is still required regardless of plan.
     */
    @Transactional(readOnly = true)
    public ObjectEconomyResponse economy(UUID objectId, UUID ownerId) {
        User user = loadUser(ownerId);
        projectService.loadOwned(objectId, ownerId); // existence + ownership (404 / 403)
        boolean enabled = featureGuard.isEnabled(user, Feature.OBJECT_ECONOMY);
        // The panels themselves are FREE-visible; the crew margin on them is NOT — it belongs to the
        // same gated tier as payments and internals, so it rides the same soft check rather than a
        // 403 of its own.
        List<SignedEstimatePanelResponse> panels = signedEstimatePanels(objectId, enabled);
        ObjectEconomyActsResponse acts = actsAxis(objectId);
        ObjectEconomyMaterialsResponse materials = materialsAxis(objectId);
        PaymentsSummaryResponse payments = enabled ? paymentService.summaryUnchecked(objectId) : null;
        ObjectEconomyInternalsResponse internals = enabled
                ? internalsOf(objectId, payments.contractedTotal())
                : null;
        return new ObjectEconomyResponse(panels, acts, materials, payments, internals);
    }

    /** The FREE-visible works axis (acts iteration): contracted / accepted-by-acts / received,
     *  computed unconditionally — the same {@code sumIncomeCounted} the payments summary uses for
     *  «За договором», so the two figures never disagree. */
    private ObjectEconomyActsResponse actsAxis(UUID objectId) {
        BigDecimal contracted = estimateRepository.sumIncomeCounted(objectId);
        // Lines + re-billed receipts: both are on the act the client signed, and both were absorbed
        // into «За договором» by ActAddendumCreator — count one without the other and the axis lies.
        BigDecimal accepted = workActItemRepository.sumSignedActLineTotals(objectId)
                .add(workActReceiptRepository.sumSignedActReceipts(objectId));
        BigDecimal received = paymentReceiptRepository.sumByProjectId(objectId);
        return new ObjectEconomyActsResponse(contracted, accepted, received);
    }

    /** The FREE-visible materials axis (V129): what the client still owes for material the
     *  master paid for at the till. Only the receipts still marked «клієнт відшкодовує» — one
     *  flipped to «моя витрата» is an {@code object_expenses} row and is counted there.
     *
     *  <p>Deliberately its own axis and NOT part of {@code contracted}/{@code acceptedByActs}:
     *  those two count one estimate set and «Прийнято актами» must stay a subset of «За
     *  договором». A receipt joins the contract only when an act picks it up.</p> */
    private ObjectEconomyMaterialsResponse materialsAxis(UUID objectId) {
        return new ObjectEconomyMaterialsResponse(
                projectReceiptRepository.sumReimbursable(objectId),
                projectReceiptRepository.countReimbursable(objectId),
                projectReceiptRepository.countUnpriced(objectId));
    }

    private ObjectEconomyInternalsResponse internalsOf(UUID objectId, BigDecimal contracted) {
        BigDecimal expenses = expenseRepository.sumAll(objectId);
        BigDecimal profit = contracted.subtract(expenses);
        return new ObjectEconomyInternalsResponse(expenses, profit);
    }

    /**
     * «Бригаді / Твоя націнка» for every marked-up copy signed on this object, keyed by estimate id.
     *
     * <p>Two queries for the whole object, not two per panel: the eligible copies come back in one
     * shot and their lines in a second. The margin already accepted by acts is one small aggregate
     * per copy — there are rarely more than a couple, and it cannot be folded into the lines query
     * because it sums over act rows, not estimate rows.</p>
     */
    private Map<UUID, CrewMarginResponse> crewMargins(UUID objectId) {
        List<Estimate> copies = estimateRepository.findSignedMarkupDuplicates(objectId);
        if (copies.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<EstimateItem>> itemsByEstimate = estimateItemRepository
                .findByEstimateIdInOrderBySortOrderAscIdAsc(copies.stream().map(Estimate::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(i -> i.getEstimate().getId()));
        Map<UUID, CrewMarginResponse> byEstimate = new LinkedHashMap<>();
        for (Estimate copy : copies) {
            CrewMarginResponse margin = CrewMarginCalculator.of(copy,
                    itemsByEstimate.getOrDefault(copy.getId(), List.of()),
                    workActItemRepository.sumSignedActMargin(copy.getId()));
            if (margin != null) {
                byEstimate.put(copy.getId(), margin);
            }
        }
        return byEstimate;
    }

    private List<SignedEstimatePanelResponse> signedEstimatePanels(UUID objectId, boolean withCrewMargin) {
        List<SignedEstimatePanelResponse> panels = new ArrayList<>();
        Map<UUID, CrewMarginResponse> margins = withCrewMargin ? crewMargins(objectId) : Map.of();
        for (Object[] row : estimateRepository.findSignedEstimateSummaries(objectId)) {
            UUID id = (UUID) row[0];
            String name = (String) row[1];
            boolean counted = Boolean.TRUE.equals(row[2]);
            Instant signedAt = toInstant(row[3]);
            BigDecimal works = toBigDecimal(row[4]);
            BigDecimal materials = toBigDecimal(row[5]);
            BigDecimal markup = toBigDecimal(row[6]);
            BigDecimal discount = toBigDecimal(row[7]);
            // works/materials are gross (pre-adjustment) now — the actual signed total adds the
            // markup back and subtracts the discount back in (discount is already negative).
            BigDecimal total = works.add(materials).add(markup).add(discount);
            BigDecimal markupRate = row[8] == null ? null : toBigDecimal(row[8]);
            BigDecimal discountRate = row[9] == null ? null : toBigDecimal(row[9]);
            EstimateKind kind = EstimateKind.valueOf((String) row[10]);
            panels.add(new SignedEstimatePanelResponse(
                    id, name, works, materials, markup, discount, total, counted, signedAt, kind,
                    markupRate, discountRate, margins.get(id)));
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

    /** Plan gate THEN ownership. Only used by the expense-journal endpoints; {@link #economy}
     *  gates just its internals. Gate is {@code Feature.OBJECT_ECONOMY} — currently granted to
     *  every plan including FREE (TEMPORARY, see the comment on {@code Plan.FREE} in {@link
     *  com.majstr.backend.feature.PlanConfig}), so this passes for everyone right now; the check
     *  stays wired so reverting the plan matrix alone re-enables the block. */
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
