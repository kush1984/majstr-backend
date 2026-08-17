package com.majstr.backend.service;

import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActItem;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.WorkActItemRepository;
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
 * first act (nothing to accumulate against yet). The figures are live and object-wide, which is why
 * the block is excluded from the canonical (hashed) PDF — see {@link WorkActPdfService.PdfModel}.</p>
 */
@Component
@RequiredArgsConstructor
class ActCumulativeCalculator {

    private final WorkActRepository actRepository;
    private final WorkActItemRepository itemRepository;
    private final EstimateRepository estimateRepository;

    /** @param items the act's own lines (already loaded by the caller) — their total is added to
     *               «виконано з початку» only while the act is not yet SIGNED (once SIGNED it is
     *               already inside {@code sumSignedActLineTotals}, so adding it would double-count). */
    WorkActPdfService.CumulativeReference forDownload(WorkAct act, List<WorkActItem> items) {
        if (!act.isShowCumulative()) {
            return null;
        }
        UUID projectId = act.getProject().getId();
        if (!actRepository.existsByProjectIdAndStatusAndIdNot(projectId, WorkActStatus.SIGNED, act.getId())) {
            return null; // first act on the object — no earlier work to reference
        }
        BigDecimal accepted = itemRepository.sumSignedActLineTotals(projectId);
        if (act.getStatus() != WorkActStatus.SIGNED) {
            accepted = accepted.add(items.stream()
                    .map(WorkActItem::getLineTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        BigDecimal contracted = estimateRepository.sumIncomeCounted(projectId);
        return new WorkActPdfService.CumulativeReference(accepted, contracted);
    }
}
