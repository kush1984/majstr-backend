package com.majstr.backend.service;

import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectReceipt;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActReceipt;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.ProjectReceiptRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What signing an act does to the object receipts that are THE SAME PAPER (review item B-04).
 *
 * <p>Both halves are conditional, and the negative cases below are the point: the stamp always
 * happens (the ADDENDUM has moved that money into «За договором», so the receivable must let go of
 * it), but dropping the object's own expense happens ONLY when the act is posting its receipts as
 * expenses. With {@code receipts_to_expenses} off the act writes no expense at all, so the object
 * receipt is the only carrier of that cost and dropping it would INFLATE profit by the receipt —
 * a new bug rather than a fix.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActReceiptReconcilerTest {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final String FN = "4000123456";

    @Mock private ProjectReceiptRepository projectReceipts;
    @Mock private WorkActReceiptRepository actReceipts;
    @Mock private ObjectExpenseRepository expenses;

    @InjectMocks private ActReceiptReconciler reconciler;

    @Test
    void anActWhoseReceiptsCarryNoIdentitySettlesNothing() {
        // Nothing is guessed from a label and an amount — only the printed fiscal code counts.
        WorkAct act = act(true);
        onTheAct(actReceipt(null, "483.50", "0.00"));

        reconciler.reconcile(act);

        verifyNoInteractions(projectReceipts);
        verifyNoInteractions(expenses);
    }

    @Test
    void theSamePaperLeavesTheReceivable() {
        WorkAct act = act(true);
        onTheAct(actReceipt("77", "483.50", "0.00"));
        ProjectReceipt atTheTill = objectReceipt("77", true);
        onTheObject(atTheTill);

        reconciler.reconcile(act);

        assertThat(atTheTill.getBilledOnActId()).isEqualTo(act.getId());
        // Reimbursable, so it never had an expense of its own — nothing to drop.
        verify(expenses, never()).deleteById(any());
    }

    @Test
    void anOwnCostPaperAlsoGivesUpItsExpense_whenTheActPostsOne() {
        WorkAct act = act(true); // receipts_to_expenses ON — the act is about to post the same cost
        onTheAct(actReceipt("77", "483.50", "0.00"));
        UUID expenseId = UUID.randomUUID();
        ProjectReceipt ownCost = objectReceipt("77", false);
        ownCost.setExpenseId(expenseId);
        onTheObject(ownCost);
        when(expenses.findByIdAndObjectId(expenseId, PROJECT))
                .thenReturn(Optional.of(ObjectExpense.builder().id(expenseId).build()));

        reconciler.reconcile(act);

        assertThat(ownCost.getBilledOnActId()).isEqualTo(act.getId());
        assertThat(ownCost.getExpenseId()).isNull();
        verify(expenses).deleteById(expenseId);
        // The row stays «моя витрата»: it still records that he paid for this paper out of pocket.
        assertThat(ownCost.isReimbursable()).isFalse();
    }

    @Test
    void anOwnCostPaperKEEPSItsExpense_whenTheActPostsNone() {
        WorkAct act = act(false); // receipts_to_expenses OFF — this receipt is the only cost record
        onTheAct(actReceipt("77", "483.50", "0.00"));
        UUID expenseId = UUID.randomUUID();
        ProjectReceipt ownCost = objectReceipt("77", false);
        ownCost.setExpenseId(expenseId);
        onTheObject(ownCost);

        reconciler.reconcile(act);

        assertThat(ownCost.getBilledOnActId()).isEqualTo(act.getId());
        assertThat(ownCost.getExpenseId()).isEqualTo(expenseId);
        verify(expenses, never()).deleteById(any());
    }

    @Test
    void aPaperAnEarlierActAlreadyBilledIsLeftAlone() {
        WorkAct act = act(true);
        onTheAct(actReceipt("77", "483.50", "0.00"));
        UUID earlierAct = UUID.randomUUID();
        ProjectReceipt already = objectReceipt("77", true);
        already.setBilledOnActId(earlierAct);
        onTheObject(already);

        reconciler.reconcile(act);

        assertThat(already.getBilledOnActId()).isEqualTo(earlierAct);
    }

    /**
     * A BLANK code is not an identity (review item B-21), and this is the case that cost money. Two
     * unrelated papers both saved with {@code ""} keyed as the same string {@code "|"}, so signing an
     * act «recognised» a receipt it had never seen: the object row lost its place on the reimbursable
     * axis and, with {@code receipts_to_expenses} on, its own-cost expense was deleted outright.
     */
    @Test
    void aBlankFiscalCodeRecognisesNothing() {
        WorkAct act = act(true);
        WorkActReceipt blankOnTheAct = actReceipt(null, "483.50", "0.00");
        blankOnTheAct.setFiscalFn("");
        blankOnTheAct.setFiscalId("");
        onTheAct(blankOnTheAct);

        reconciler.reconcile(act);

        // Not even looked for: an act carrying no identity has nothing to match against.
        verifyNoInteractions(projectReceipts);
        verifyNoInteractions(expenses);
    }

    /** The other half of the same bug: the blank row is on the OBJECT, the act's code is real. */
    @Test
    void aBlankObjectPaperIsNotTheActsPaper() {
        WorkAct act = act(true);
        onTheAct(actReceipt("77", "483.50", "0.00"));
        ProjectReceipt blankAtTheTill = objectReceipt("77", false);
        blankAtTheTill.setFiscalFn("  ");
        blankAtTheTill.setFiscalId("  ");
        blankAtTheTill.setExpenseId(UUID.randomUUID());
        onTheObject(blankAtTheTill);

        reconciler.reconcile(act);

        assertThat(blankAtTheTill.getBilledOnActId()).isNull();
        assertThat(blankAtTheTill.getExpenseId()).isNotNull();
        verify(expenses, never()).deleteById(any());
    }

    /** Returned to the shop in full (V115): no ADDENDUM line, no expense — so nothing to settle. */
    @Test
    void aFullyReturnedActReceiptSettlesNothing() {
        WorkAct act = act(true);
        onTheAct(actReceipt("77", "483.50", "483.50"));
        ProjectReceipt atTheTill = objectReceipt("77", true);
        onTheObject(atTheTill);

        reconciler.reconcile(act);

        assertThat(atTheTill.getBilledOnActId()).isNull();
    }

    // ---- fixtures ---------------------------------------------------------

    private static WorkAct act(boolean receiptsToExpenses) {
        WorkAct act = WorkAct.builder()
                .id(UUID.randomUUID()).number("7")
                .project(Project.builder().id(PROJECT).build())
                .build();
        act.setReceiptsToExpenses(receiptsToExpenses);
        return act;
    }

    private void onTheAct(WorkActReceipt... rows) {
        when(actReceipts.findByWorkActIdNewestFirst(any())).thenReturn(List.of(rows));
    }

    private void onTheObject(ProjectReceipt... rows) {
        when(projectReceipts.findIdentifiedByProjectId(PROJECT)).thenReturn(List.of(rows));
    }

    private static WorkActReceipt actReceipt(String fiscalId, String amount, String returned) {
        WorkActReceipt r = WorkActReceipt.builder()
                .id(UUID.randomUUID()).label("Цвяхи")
                .amount(new BigDecimal(amount))
                .returnedAmount(new BigDecimal(returned))
                .build();
        if (fiscalId != null) {
            r.setFiscalFn(FN);
            r.setFiscalId(fiscalId);
        }
        return r;
    }

    private static ProjectReceipt objectReceipt(String fiscalId, boolean reimbursable) {
        ProjectReceipt r = ProjectReceipt.builder()
                .id(UUID.randomUUID()).projectId(PROJECT).label("Епіцентр")
                .amount(new BigDecimal("483.50"))
                .build();
        r.setReimbursable(reimbursable);
        r.setFiscalFn(FN);
        r.setFiscalId(fiscalId);
        return r;
    }
}
