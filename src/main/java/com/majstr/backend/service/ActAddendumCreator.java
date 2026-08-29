package com.majstr.backend.service;

import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateKind;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ExpenseCategory;
import com.majstr.backend.entity.ExpenseSource;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActItem;
import com.majstr.backend.entity.WorkActReceipt;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.WorkActItemRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * What a signed act writes back into the object's economy, shared by BOTH sign paths — the offline
 * {@link WorkActService#signOffline} and the client-facing {@link PublicActPortalService#sign}:
 *
 * <ul>
 *   <li>the act's ADDITIONAL (off-estimate) positions AND its re-billed receipts are rolled into a
 *       SIGNED, counted, non-shared ADDENDUM estimate, so «За договором» absorbs them and «Прийнято
 *       актами» can never exceed it (the invariant enforced in {@code ObjectExpenseService.actsAxis});</li>
 *   <li>each receipt is optionally posted as a MATERIALS expense ({@code receipts_to_expenses}) so
 *       «Прибуток» is not inflated by pass-through money the client merely reimburses.</li>
 * </ul>
 *
 * <p>Throughout, «a receipt» means <b>paid less returned</b> ({@link WorkActReceipt#billedAmount()},
 * V115). The same figure reaches the client's bill (the ADDENDUM line) and the master's expenses,
 * and it is the one {@code sumSignedActReceipts} adds into «Прийнято актами» — material handed back
 * to the shop was accepted by nobody and paid for by nobody. A receipt returned in full bills
 * nothing at all and is skipped on both sides.</p>
 */
@Component
@RequiredArgsConstructor
class ActAddendumCreator {

    private static final int MONEY_SCALE = 2;

    private final WorkActItemRepository itemRepository;
    private final WorkActReceiptRepository receiptRepository;
    private final EstimateRepository estimateRepository;
    private final EstimateItemRepository estimateItemRepository;
    private final ObjectExpenseRepository expenseRepository;

    /** Must run in the SAME transaction as the sign, BEFORE the act is stamped SIGNED (there is no
     *  per-row immutability guard, but keeping it pre-SIGNED matches the offline path's ordering). */
    void createIfNeeded(WorkAct act) {
        List<WorkActItem> additional = itemRepository.findByWorkActIdOrderBySortOrderAscIdAsc(act.getId())
                .stream().filter(i -> i.getEstimateItemId() == null).toList();
        List<WorkActReceipt> allReceipts = receiptRepository
                .findByWorkActIdNewestFirst(act.getId());
        // ITEMIZED receipts (round 2) are already inside the act as its own lines — rolling them up
        // here too would bill the same money twice. The EXPENSE posting below deliberately takes
        // them all: the master's own spend is real whichever way the client is billed.
        // A fully returned receipt (V115) bills nothing: no ADDENDUM line, and no expense either —
        // the money left the master's pocket and came back to it.
        List<WorkActReceipt> receipts = allReceipts.stream()
                .filter(r -> !r.isItemized())
                .filter(r -> r.billedAmount().signum() > 0)
                .toList();
        if (additional.isEmpty() && allReceipts.isEmpty()) {
            return; // nothing on the act beyond the estimate positions it closes
        }
        List<WorkActReceipt> spent = allReceipts.stream()
                .filter(r -> r.billedAmount().signum() > 0).toList();
        if (!spent.isEmpty() && act.isReceiptsToExpenses()) {
            postReceiptExpenses(act, spent);
        }
        if (additional.isEmpty() && receipts.isEmpty()) {
            return; // only itemized receipts — expenses posted, nothing to roll up
        }
        Estimate addendum = estimateRepository.save(Estimate.builder()
                .project(act.getProject())
                .name(addendumName(act, additional, receipts))
                .status(EstimateStatus.SIGNED)
                .kind(EstimateKind.ADDENDUM)
                .countInEconomy(true)
                .portalVisible(false)
                .economyVisible(false)
                .signedAt(Instant.now())
                .build());
        List<EstimateItem> lines = new ArrayList<>();
        int sort = 0;
        for (WorkActItem a : additional) {
            lines.add(EstimateItem.builder()
                    .estimate(addendum)
                    .type(a.getType())
                    .name(a.getName())
                    .category(a.getCategory())
                    .unit(a.getUnit())
                    .quantity(a.getQuantity())
                    .unitPrice(a.getUnitPrice())
                    .sortOrder(sort++)
                    .build());
        }
        // A receipt is re-billed as a whole — one MATERIAL line at quantity 1, never its parsed
        // contents (the master explicitly did not want the receipt's positions inside the act).
        // «Whole» means paid less returned (V115): the client pays for the material that stayed on
        // the object, and the ADDENDUM must agree with «Разом за чеками» to the kopeck.
        for (WorkActReceipt r : receipts) {
            lines.add(EstimateItem.builder()
                    .estimate(addendum)
                    .type(ItemType.MATERIAL)
                    .name("Чек: " + r.getLabel())
                    .category("Матеріали за чеками")
                    .unit(Unit.PIECE)
                    .quantity(BigDecimal.ONE)
                    .unitPrice(r.billedAmount())
                    .sortOrder(sort++)
                    .build());
        }
        EstimateMath.recalculate(lines); // fills line_total
        estimateItemRepository.saveAll(lines);
        act.setAddendumEstimateId(addendum.getId());
    }

    private void postReceiptExpenses(WorkAct act, List<WorkActReceipt> receipts) {
        List<ObjectExpense> expenses = new ArrayList<>(receipts.size());
        for (WorkActReceipt r : receipts) {
            expenses.add(ObjectExpense.builder()
                    .objectId(act.getProject().getId())
                    .amount(r.billedAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                    .category(ExpenseCategory.MATERIALS)
                    .source(ExpenseSource.RECEIPT)
                    .note("Чек до акта № " + act.getNumber() + ": " + r.getLabel())
                    .spentAt(r.getIssuedAt() == null ? LocalDate.now() : r.getIssuedAt())
                    .build());
        }
        expenseRepository.saveAll(expenses);
    }

    private static String addendumName(WorkAct act, List<WorkActItem> additional, List<WorkActReceipt> receipts) {
        if (additional.isEmpty()) {
            return "Матеріали за чеками до акта № " + act.getNumber();
        }
        if (receipts.isEmpty()) {
            return "Додаткові роботи до акта № " + act.getNumber();
        }
        return "Додатково до акта № " + act.getNumber();
    }
}
