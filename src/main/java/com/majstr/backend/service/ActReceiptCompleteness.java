package com.majstr.backend.service;

import com.majstr.backend.exception.WorkActValidationException;
import com.majstr.backend.repository.WorkActReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The one guard that makes «save the photo first, price it later» safe (receipts-batch iteration).
 *
 * <p>A receipt is now created the moment its photo is picked, with amount 0 — that is the answer to
 * the master's «з недостатньою швидкістю інтернету довго думає і додавати чек не хоче»: the paper is
 * stored before the QR read or the model call that fills in the money. The cost is that an act can
 * legitimately hold a receipt worth nothing yet, and such a receipt must never reach a document:
 * once the act is SENT the client can sign it, and a SIGNED act is immutable, hashed, and rolls its
 * receipts into a SIGNED ADDENDUM estimate. A 0 ₴ line there is a receipt the master silently gave
 * away.</p>
 *
 * <p>So both doors are guarded — publishing to the client link, and BOTH sign paths (offline and
 * portal). Editing is deliberately NOT blocked (master decision, 2026-08-24): an unpriced receipt is
 * work in progress, not an error, and the PWA names the offenders in place.</p>
 *
 * <p>Its own component rather than a method on {@link WorkActReceiptService} because that service
 * depends on {@link WorkActService}, and the guard is needed inside it — the injection would be a
 * cycle.</p>
 */
@Component
@RequiredArgsConstructor
public class ActReceiptCompleteness {

    private final WorkActReceiptRepository receiptRepository;

    public void requireAllPriced(UUID actId) {
        if (receiptRepository.existsUnpricedByWorkActId(actId)) {
            throw new WorkActValidationException(
                    "error.work-act.receipt-unpriced", "WORK_ACT_RECEIPT_UNPRICED");
        }
    }
}
