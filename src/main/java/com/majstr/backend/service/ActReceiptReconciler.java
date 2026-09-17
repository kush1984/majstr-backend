package com.majstr.backend.service;

import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.ProjectReceipt;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActReceipt;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.ProjectReceiptRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * When an act is signed, settle the object receipts that turn out to be THE SAME PAPER as one of the
 * act's receipts (review item B-04, item 5).
 *
 * <p>This runs by itself, with no question asked, because the printed fiscal identity is not a guess:
 * two rows carrying the same {@code fn} + {@code id} are one slip from one till. Asking would only
 * push an arithmetic decision onto a master who is standing in front of a client with a phone.</p>
 *
 * <h4>What it does, and why each half is conditional</h4>
 * <p>Working from {@code Прибуток = «За договором» − Σ object_expense}, for a paper present in both
 * tables when the act is signed:</p>
 * <ul>
 *   <li><b>Always: the object receipt is stamped {@code billed_on_act_id}</b>, which takes it out of
 *       the «клієнт відшкодовує» receivable. The act's ADDENDUM has just moved that exact money into
 *       «За договором»; leaving the row in the materials axis as well would show the master the same
 *       debt in two places and invite him to ask for it twice.</li>
 *   <li><b>Only when the act posts its receipts as expenses</b> ({@code receipts_to_expenses}):
 *       an object receipt marked «моя витрата» has its own MATERIALS expense dropped, because the act
 *       is about to post the same cost. Two expenses against one {@code +X} in «За договором»
 *       understates profit by exactly the receipt — the double count B-04 is named after.</li>
 * </ul>
 * <p>The condition is load-bearing in the other direction too: with {@code receipts_to_expenses} OFF
 * the act writes no expense at all, so the object receipt is the ONLY carrier of that cost, and
 * dropping it would INFLATE profit by the same amount. A blanket «flip it, they're duplicates» would
 * therefore be a new bug rather than a fix.</p>
 *
 * <p><b>Nothing here throws.</b> It runs inside the transaction that signs the act — including the
 * client-facing portal sign — and no bookkeeping tidy-up may cost a master a signature. A receipt
 * that cannot be settled simply stays as it was, and the read path still warns about it.</p>
 *
 * <p>Already-signed acts are deliberately NOT re-scanned: their history is frozen, and silently
 * restating a master's past profit is worse than leaving the gap visible.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ActReceiptReconciler {

    private final ProjectReceiptRepository projectReceipts;
    private final WorkActReceiptRepository actReceipts;
    private final ObjectExpenseRepository expenses;

    /**
     * @param act the act being signed, before it is stamped SIGNED
     *
     * <p>The act's receipts are loaded HERE rather than handed in, for the reason both sign paths
     * exist as one {@link ActAddendumCreator} call: which receipts count is a rule, and a rule each
     * caller re-states is a rule that drifts. A receipt returned to the shop in full
     * ({@code billedAmount() == 0}, V115) moved no money in either table, so it settles nothing —
     * the same filter the ADDENDUM applies, and it has to be the same or one of them would be
     * reasoning about money the other did not bill.</p>
     */
    void reconcile(WorkAct act) {
        List<WorkActReceipt> billed = actReceipts.findByWorkActIdNewestFirst(act.getId()).stream()
                .filter(r -> r.billedAmount().signum() > 0)
                .toList();
        Set<String> keys = new HashSet<>();
        for (WorkActReceipt r : billed) {
            String key = keyOf(r.getFiscalFn(), r.getFiscalId());
            if (key != null) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            return; // no identified paper on this act — nothing can be matched, and nothing is guessed
        }
        UUID projectId = act.getProject().getId();
        List<ProjectReceipt> settled = new ArrayList<>();
        for (ProjectReceipt r : projectReceipts.findIdentifiedByProjectId(projectId)) {
            if (r.getBilledOnActId() != null || !keys.contains(keyOf(r.getFiscalFn(), r.getFiscalId()))) {
                continue; // already billed on an earlier act, or a different paper
            }
            r.setBilledOnActId(act.getId());
            if (act.isReceiptsToExpenses() && !r.isReimbursable()) {
                dropExpense(r);
            }
            settled.add(r);
        }
        if (!settled.isEmpty()) {
            log.info("Act {} billed {} object receipt(s) already filed on object {}",
                    act.getId(), settled.size(), projectId);
        }
    }

    /**
     * Remove the own-cost expense this object receipt posted. The row stays {@code reimbursable =
     * false} on purpose: it still records that the master paid for this paper out of pocket, and the
     * response already has a name for «own cost whose expense is gone» ({@code hasExpense = false}).
     * Deleting it by hand from the journal is allowed, so a missing row is not an error.
     */
    private void dropExpense(ProjectReceipt receipt) {
        UUID expenseId = receipt.getExpenseId();
        receipt.setExpenseId(null);
        if (expenseId == null) {
            return;
        }
        expenses.findByIdAndObjectId(expenseId, receipt.getProjectId())
                .map(ObjectExpense::getId)
                .ifPresent(expenses::deleteById);
    }

    private static String keyOf(String fn, String id) {
        return fn == null || id == null ? null : fn + "|" + id;
    }
}
