package com.majstr.backend.service;

import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.exception.WorkActValidationException;
import com.majstr.backend.repository.WorkActItemRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * «Зарахувати аванс» may not exceed what the act bills (review B-78).
 *
 * <p>The PDF prints {@code max(0, total + receipts − advance)}, so an advance larger than the act
 * silently became «До сплати 0 ₴»: the master offsets a 30 000 ₴ prepayment on a 20 000 ₴ act and
 * the document tells the client he owes nothing, with the remaining 10 000 ₴ recorded nowhere. The
 * figure is clamped rather than refused because a bill cannot be negative — which is exactly why
 * the refusal has to happen before the clamp.</p>
 *
 * <p><b>Master-facing doors only</b>: the header save (immediate, he is looking at the field), the
 * publish to SENT and {@code signOffline}. The client's portal sign is deliberately NOT guarded —
 * the same reason {@code ActFinalGuard} leaves it alone (B-62, B-28): an error only the master can
 * fix must never land on the client's tap.</p>
 *
 * <p>What this deliberately does NOT do is the review's second half — «Σ advances of signed acts ≤
 * work-only received». V115 settled that {@code advance_offset} is a DOCUMENT-ONLY figure that
 * nothing in the economy reads or reconciles, and the editor only SUGGESTS it from «Мої гроші»,
 * non-blocking. Reconciling it across acts here would make a save fail over a payment recorded
 * (or not recorded) somewhere else entirely, which is the shape of refusal that gets lied to.</p>
 */
@Component
@RequiredArgsConstructor
public class ActAdvanceGuard {

    private static final int MONEY_SCALE = 2;

    private final WorkActItemRepository itemRepository;
    private final WorkActReceiptRepository receiptRepository;

    /** The act as it stands now. Called after the lines/receipts/advance of this request landed. */
    public void requireAdvanceWithinAct(WorkAct act) {
        BigDecimal advance = act.getAdvanceOffset();
        if (advance == null || advance.signum() <= 0) {
            return;
        }
        BigDecimal billed = zero()
                .add(orZero(itemRepository.sumLineTotalsByWorkActId(act.getId())))
                .add(orZero(receiptRepository.sumByWorkActId(act.getId())));
        if (advance.compareTo(billed) > 0) {
            throw new WorkActValidationException(
                    "error.work-act.advance-over-total", "WORK_ACT_ADVANCE_OVER_TOTAL");
        }
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? zero() : value;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
