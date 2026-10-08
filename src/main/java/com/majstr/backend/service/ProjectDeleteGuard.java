package com.majstr.backend.service;

import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.exception.ProjectHasSignedMoneyException;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.PaymentReceiptRepository;
import com.majstr.backend.repository.ProjectReceiptRepository;
import com.majstr.backend.repository.WorkActRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * An object that carries a signature or money is not deletable — it is ARCHIVED (review B-70,
 * owner's answer).
 *
 * <p>Every other door already refuses this: a SIGNED estimate cannot be deleted
 * ({@code EstimateService.requireNotSigned}), a signed act cannot be deleted, and «виключити з
 * економіки» is refused while acts stand on an estimate. The object delete walked past all three at
 * once — it cascades the estimates, the acts, their ADDENDUMs, the payment receipts and the
 * expenses. And «Мої гроші» is a LENS over exactly those rows, so deleting a finished object
 * silently rewrote the master's closed months: last spring's earnings changed because he tidied up
 * a job from last spring.</p>
 *
 * <p>Nothing new had to be built to offer an alternative. A terminal object (COMPLETED/CANCELLED)
 * is already hidden from «Усі» behind the archived reveal, and the permanent delete is offered
 * ONLY on a terminal object — so by the time a master can reach this refusal, the object is
 * already out of his way. The 409 says the papers stay, not «try again».</p>
 *
 * <p>What counts as «money» is deliberately the set whose loss would move a figure the master has
 * already read: a SIGNED estimate, a SIGNED act, any {@code payment_receipt}, any
 * {@code object_expenses} row, and any {@code project_receipt} worth money. The last was missing
 * until review B-101: a reimbursable till receipt writes no expense (V129), but «Мої гроші» has
 * counted it as an outlay since B-33, so deleting the object moved last month's «Заробив» by the
 * receipt.</p>
 */
@Component
@RequiredArgsConstructor
class ProjectDeleteGuard {

    private final EstimateRepository estimateRepository;
    private final WorkActRepository workActRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final ObjectExpenseRepository objectExpenseRepository;
    private final ProjectReceiptRepository projectReceiptRepository;

    /** Refuses when deleting the object would take a signature or recorded money with it. */
    void requireNoSignedMoney(UUID projectId) {
        if (estimateRepository.existsByProjectIdAndStatus(projectId, EstimateStatus.SIGNED)
                || workActRepository.existsByProjectIdAndStatusIn(
                        projectId, java.util.List.of(WorkActStatus.SIGNED))
                || paymentReceiptRepository.existsByProjectId(projectId)
                || objectExpenseRepository.existsByObjectId(projectId)
                || projectReceiptRepository.existsByProjectIdAndAmountGreaterThan(projectId, java.math.BigDecimal.ZERO)) {
            throw new ProjectHasSignedMoneyException();
        }
    }
}
