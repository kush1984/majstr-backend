package com.majstr.backend.service;

import com.majstr.backend.dto.FiscalIdentity;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActReceipt;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.exception.WorkActConflictException;
import com.majstr.backend.repository.WorkActReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * One slip, one act (review B-79).
 *
 * <p>V134 made the same paper visible across the two receipt tables and {@code ActReceiptReconciler}
 * settles an object receipt against the act that bills it. What nothing refused was the SAME slip on
 * TWO acts: a receipt photographed once and attached to act 3 and again to act 4 was shown as a
 * «duplicate» warning on both and then billed on both — the client paid 4 800 ₴ for a 2 400 ₴
 * purchase, and {@code receipts_to_expenses} posted the cost twice as well.</p>
 *
 * <p>The identity is authoritative and asks nothing, exactly as V134 decided: one {@code fn} + one
 * {@code id} is one piece of paper, so there is no override flag to offer. The way out is to correct
 * the receipt — delete it from the act that should not carry it, or clear the code if the master
 * really did photograph two different papers whose codes we read wrongly.</p>
 *
 * <p><b>Only an already-SIGNED act counts.</b> Two OPEN acts cannot coexist on an object anyway, and
 * a REJECTED act bills nothing. Checked at the master's doors — the publish and {@code signOffline}
 * — and also at the portal signature, which is the one place where refusing the CLIENT is still
 * the lesser harm: the alternative is billing him twice for the same slip.</p>
 */
@Component
@RequiredArgsConstructor
public class ActReceiptDuplicateGuard {

    private final WorkActReceiptRepository receiptRepository;

    public void requireNoReceiptBilledElsewhere(WorkAct act) {
        Map<String, WorkActReceipt> signedElsewhere = new HashMap<>();
        for (WorkActReceipt r : receiptRepository.findIdentifiedByProjectId(act.getProject().getId())) {
            if (r.getWorkAct().getId().equals(act.getId())
                    || r.getWorkAct().getStatus() != WorkActStatus.SIGNED) {
                continue;
            }
            signedElsewhere.putIfAbsent(keyOf(r), r);
        }
        if (signedElsewhere.isEmpty()) {
            return;
        }
        for (WorkActReceipt mine : receiptRepository.findByWorkActIdNewestFirst(act.getId())) {
            if (!FiscalIdentity.complete(mine.getFiscalFn(), mine.getFiscalId())) {
                continue;
            }
            WorkActReceipt twin = signedElsewhere.get(keyOf(mine));
            if (twin != null) {
                throw new WorkActConflictException("error.work-act.receipt-already-billed",
                        "WORK_ACT_RECEIPT_ALREADY_BILLED");
            }
        }
    }

    /** {@link FiscalIdentity} is the ONE definition of the key — never a local concatenation (B-21). */
    private static String keyOf(WorkActReceipt r) {
        return FiscalIdentity.key(r.getFiscalFn(), r.getFiscalId());
    }
}
