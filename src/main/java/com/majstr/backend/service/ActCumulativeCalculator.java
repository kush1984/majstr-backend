package com.majstr.backend.service;

import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActItem;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.WorkActItemRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import com.majstr.backend.repository.WorkActRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Builds the «ДОВІДКОВО» reference figures for a work-act PDF from the SAME queries that feed the
 * economy works axis ({@code sumSignedActLineTotals} + {@code sumIncomeCounted}) — a single source so
 * the PDF and the app can never show a different «виконано з початку» / «за кошторисами». Shared by
 * both PDF-render paths (owner download and the public act portal).
 *
 * <p>Returns {@code null} whenever the block must not render: the master left it off, or this is the
 * first act (nothing to accumulate against yet). The figures are object-wide and are excluded from
 * the canonical (hashed) PDF — see {@link WorkActPdfService.PdfModel} — because they are not part of
 * what the signature certifies.</p>
 *
 * <p><b>Two different questions, by status</b> (review B-77). While the act is open the block answers
 * «what will this object stand at once this act is accepted», so the act's own lines and receipts are
 * added to BOTH sides — the contract side too, since signing creates the ADDENDUM that carries the
 * off-estimate half. Once the act is SIGNED it answers «what did it stand at then», computed as of
 * {@code signed_at}: a document the client already holds must render the same figures tomorrow.</p>
 */
@Component
@RequiredArgsConstructor
class ActCumulativeCalculator {

    private final WorkActRepository actRepository;
    private final WorkActItemRepository itemRepository;
    private final WorkActReceiptRepository receiptRepository;
    private final EstimateRepository estimateRepository;

    /** @param items the act's own lines (already loaded by the caller) — their total is added to
     *               «виконано з початку» only while the act is not yet SIGNED (once SIGNED it is
     *               already inside {@code sumSignedActLineTotals}, so adding it would double-count).
     *  @param ownReceiptsTotal the act's own receipts, added under exactly the same rule. */
    WorkActPdfService.CumulativeReference forDownload(WorkAct act, List<WorkActItem> items,
                                                      BigDecimal ownReceiptsTotal) {
        if (!act.isShowCumulative()) {
            return null;
        }
        UUID projectId = act.getProject().getId();
        if (!actRepository.existsByProjectIdAndStatusAndIdNot(projectId, WorkActStatus.SIGNED, act.getId())) {
            return null; // first act on the object — no earlier work to reference
        }
        if (act.getStatus() == WorkActStatus.SIGNED && act.getSignedAt() != null) {
            // AS OF ITS OWN SIGNATURE (review B-77). The figures are live, which is right while the
            // act is being prepared and wrong the moment it is history: re-downloading act 3 after
            // act 4 was signed printed act 3's «виконано з початку» including act 4's work, so a
            // document the client already holds said something different every time it was rendered.
            return new WorkActPdfService.CumulativeReference(
                    itemRepository.sumSignedActLineTotalsAsOf(projectId, act.getSignedAt())
                            .add(receiptRepository.sumSignedActReceiptsAsOf(projectId, act.getSignedAt())),
                    estimateRepository.sumIncomeCountedAsOf(projectId, act.getSignedAt()));
        }
        BigDecimal ownLines = items.stream()
                .map(WorkActItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ownReceipts = ownReceiptsTotal == null ? BigDecimal.ZERO : ownReceiptsTotal;
        BigDecimal accepted = itemRepository.sumSignedActLineTotals(projectId)
                .add(receiptRepository.sumSignedActReceipts(projectId))
                .add(ownLines)
                .add(ownReceipts);
        // The act is not signed yet, so «За договором» does not know about what signing it will ADD
        // (review B-77): off-estimate lines and the act's receipts become a SIGNED ADDENDUM at that
        // moment ({@code ActAddendumCreator}), which is part of the contract. Counting them in
        // `accepted` but not in `contracted` printed «Залишок −5 000 ₴» on the page the client reads
        // before he signs. ADJUSTMENT lines are deliberately NOT added: they carry a share of an
        // estimate's own discount, which `sumIncomeCounted` already measured in full.
        BigDecimal futureAddendum = items.stream()
                .filter(i -> i.getLineKind() == com.majstr.backend.entity.WorkActLineKind.ADDITIONAL)
                .map(WorkActItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(ownReceipts);
        BigDecimal contracted = estimateRepository.sumIncomeCounted(projectId).add(futureAddendum);
        return new WorkActPdfService.CumulativeReference(accepted, contracted);
    }
}
