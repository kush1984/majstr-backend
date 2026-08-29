package com.majstr.backend.service;

import com.majstr.backend.dto.WorkActItemResponse;
import com.majstr.backend.dto.WorkActReceiptResponse;
import com.majstr.backend.dto.WorkActResponse;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActItem;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.WorkActItemRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds a {@link WorkActResponse} from a managed {@link WorkAct} — extracted into its own bean so
 * {@link WorkActCreator} (which runs each create in its own transaction) can build the response
 * INSIDE that transaction. {@link WorkActService#create} is deliberately non-transactional (the
 * numbering retry must live outside a transaction), so it cannot build the response itself without
 * hitting a lazy-loading error on the act's project. <b>Must be called within a transaction.</b>
 */
@Component
@RequiredArgsConstructor
class WorkActResponseFactory {

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final WorkActItemRepository itemRepository;
    private final EstimateItemRepository estimateItemRepository;
    private final WorkActReceiptRepository receiptRepository;

    WorkActResponse build(WorkAct act) {
        List<WorkActItem> items = itemRepository.findByWorkActIdOrderBySortOrderAscIdAsc(act.getId());
        // Current quantities of the estimate lines these close, for the live exceedsEstimate flag.
        Map<UUID, BigDecimal> estimateQty = new HashMap<>();
        List<UUID> refIds = items.stream().map(WorkActItem::getEstimateItemId).filter(Objects::nonNull).toList();
        if (!refIds.isEmpty()) {
            estimateItemRepository.findAllById(refIds)
                    .forEach(ei -> estimateQty.put(ei.getId(), ei.getQuantity()));
        }
        BigDecimal total = BigDecimal.ZERO.setScale(MONEY_SCALE, ROUNDING);
        List<WorkActItemResponse> itemDtos = new ArrayList<>(items.size());
        for (WorkActItem it : items) {
            total = total.add(it.getLineTotal());
            boolean exceeds = it.getEstimateItemId() != null
                    && estimateQty.containsKey(it.getEstimateItemId())
                    && it.getCumulativeBefore().add(it.getQuantity())
                        .compareTo(estimateQty.get(it.getEstimateItemId())) > 0;
            itemDtos.add(WorkActItemResponse.from(it, exceeds));
        }
        List<WorkActReceiptResponse> receipts = receiptRepository
                .findByWorkActIdNewestFirst(act.getId()).stream()
                .map(WorkActReceiptResponse::from)
                .toList();
        // Itemized receipts are reference-only (their positions already bill the money as act
        // lines, round 2) — they show in the list but never in the billed subtotal. Every other
        // receipt bills paid-less-returned (V115), so a partly returned one shrinks in place.
        BigDecimal receiptsTotal = receipts.stream()
                .filter(r -> !r.itemized())
                .map(WorkActReceiptResponse::billedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(MONEY_SCALE, ROUNDING);
        BigDecimal advance = act.getAdvanceOffset() == null
                ? BigDecimal.ZERO.setScale(MONEY_SCALE, ROUNDING) : act.getAdvanceOffset();
        BigDecimal payable = total.add(receiptsTotal).subtract(advance)
                .max(BigDecimal.ZERO).setScale(MONEY_SCALE, ROUNDING);
        return new WorkActResponse(
                act.getId(),
                act.getProject().getId(),
                act.getNumber(),
                act.getTitle(),
                act.getKind(),
                act.getStatus(),
                act.getIssuedAt(),
                act.getPeriodFrom(),
                act.getPeriodTo(),
                act.getPlace(),
                act.getContractRef(),
                act.getNote(),
                act.isShowMaterials(),
                act.isShowCumulative(),
                act.isReceiptsToExpenses(),
                act.isShowReceiptPhotos(),
                act.getAdvanceOffset(),
                act.getRetentionPercent(),
                act.getSentAt(),
                act.getSignedAt(),
                act.getSignerName(),
                act.isSignedOffline(),
                act.getAddendumEstimateId(),
                itemDtos,
                receipts,
                total,
                receiptsTotal,
                payable,
                act.getCreatedAt(),
                act.getUpdatedAt()
        );
    }
}
