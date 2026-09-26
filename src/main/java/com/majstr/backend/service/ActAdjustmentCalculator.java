package com.majstr.backend.service;

import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.PercentBaseKind;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActItem;
import com.majstr.backend.entity.WorkActLineKind;
import com.majstr.backend.repository.EstimateItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An estimate's discounts and surcharges, carried into the acts that close it (review B-55).
 *
 * <p>«Знижка −10 % від кошторису» is part of «За договором» — the client signed 18 000, not 20 000.
 * An act, however, is built from POSITIONS, and a «%» line has no quantity to close, so acts billed
 * the gross prices: close the whole 20 000 estimate and «Прийнято актами» read 20 000 against a
 * contract of 18 000. The client was billed 2 000 he never agreed to, and the one invariant the
 * acts chain exists to keep — «Прийнято ⊆ За договором» — broke in the direction that costs him
 * money. A positive «%» (транспортні) failed the other way: it could never be closed at all, and
 * billing it as an ADDITIONAL line counted it twice.</p>
 *
 * <h2>The rule</h2>
 * One server-authored line per estimate and per type, carrying that estimate's percentages
 * <b>prorated by what THIS act closes</b>. The proration mirrors {@link EstimateMath}'s three-step
 * pass exactly, one step at a time, because the two numbers have to meet: when the acts have closed
 * every position of an estimate, each percentage is carried in full and «Прийнято актами» equals
 * «За договором» to the kopeck. Close half, and half the discount travels with it.
 *
 * <ol>
 *   <li>ordinary lines: closed = what this act bills for that position;</li>
 *   <li>a percentage of a POSITION follows its base line's own ratio; a legacy MANUAL one (V88
 *       retired the kind) has no base to follow, so it takes its type's overall ratio;</li>
 *   <li>a percentage of the ESTIMATE follows the ratio of its own type's subtotal — the same
 *       subtotal {@link EstimateMath} measured it against, closed lines and step-2 percentages
 *       included.</li>
 * </ol>
 *
 * <p>The result is an ordinary {@link WorkActItem} — a frozen row like every other, so the act
 * total, the PDF, «ДОВІДКОВО» and {@code sumSignedActLineTotals} all pick it up with no special
 * case. It is marked {@link WorkActLineKind#ADJUSTMENT} because it carries an {@code estimate_id}
 * with no {@code estimate_item_id}, which used to mean «additional work» and would have sent the
 * discount into the ADDENDUM.</p>
 */
@Component
@RequiredArgsConstructor
class ActAdjustmentCalculator {

    private static final int MONEY_SCALE = 2;
    private static final int QUANTITY_SCALE = 3;
    private static final int RATIO_SCALE = 10;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final EstimateItemRepository estimateItemRepository;

    /**
     * The adjustment lines for an act whose ordinary lines have just been built.
     *
     * @param lines          the act's own lines, already priced — only ESTIMATE-linked ones are read
     * @param firstSortOrder where to continue numbering (adjustments close the act)
     */
    List<WorkActItem> adjustmentsFor(WorkAct act, List<WorkActItem> lines, int firstSortOrder) {
        Set<UUID> estimateIds = new LinkedHashSet<>();
        for (WorkActItem line : lines) {
            if (line.getLineKind() == WorkActLineKind.ESTIMATE && line.getEstimateId() != null) {
                estimateIds.add(line.getEstimateId());
            }
        }
        if (estimateIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<EstimateItem>> byEstimate = new LinkedHashMap<>();
        for (EstimateItem item : estimateItemRepository
                .findByEstimateIdInOrderBySortOrderAscIdAsc(estimateIds)) {
            byEstimate.computeIfAbsent(item.getEstimate().getId(), k -> new ArrayList<>()).add(item);
        }
        List<WorkActItem> adjustments = new ArrayList<>();
        int sort = firstSortOrder;
        for (UUID estimateId : estimateIds) {
            List<EstimateItem> estimateItems = byEstimate.get(estimateId);
            if (estimateItems == null) {
                continue; // the estimate's lines are gone; the act's frozen rows still stand
            }
            Map<ItemType, BigDecimal> perType =
                    adjustmentsPerType(estimateItems, closed(lines, estimateId));
            for (Map.Entry<ItemType, BigDecimal> e : perType.entrySet()) {
                BigDecimal amount = e.getValue().setScale(MONEY_SCALE, ROUNDING);
                if (amount.signum() == 0) {
                    continue; // nothing to say
                }
                adjustments.add(WorkActItem.builder()
                        .workAct(act)
                        .lineKind(WorkActLineKind.ADJUSTMENT)
                        .estimateItemId(null)
                        .estimateId(estimateId)
                        .type(e.getKey())
                        .name(amount.signum() < 0 ? "Знижка за кошторисом" : "Надбавка за кошторисом")
                        .category(null)
                        .unit(Unit.PIECE)
                        .unitPrice(amount)
                        .quantity(BigDecimal.ONE.setScale(QUANTITY_SCALE))
                        .lineTotal(amount)
                        .cumulativeBefore(BigDecimal.ZERO.setScale(QUANTITY_SCALE))
                        .sortOrder(sort++)
                        .build());
            }
        }
        return adjustments;
    }

    /** What this act bills against each position of one estimate. */
    private static Map<UUID, BigDecimal> closed(List<WorkActItem> lines, UUID estimateId) {
        Map<UUID, BigDecimal> map = new HashMap<>();
        for (WorkActItem line : lines) {
            if (line.getLineKind() == WorkActLineKind.ESTIMATE
                    && estimateId.equals(line.getEstimateId())
                    && line.getEstimateItemId() != null) {
                map.merge(line.getEstimateItemId(), line.getLineTotal(), BigDecimal::add);
            }
        }
        return map;
    }

    /**
     * {@link EstimateMath}'s pass, run over the CLOSED share instead of the whole estimate. One
     * amount per type, works before materials as the summary card splits them.
     */
    private static Map<ItemType, BigDecimal> adjustmentsPerType(
            List<EstimateItem> items, Map<UUID, BigDecimal> closedByItem) {
        // 1. Ordinary lines — and their type subtotals, which both fallbacks below measure.
        Map<ItemType, BigDecimal> ordinaryBase = new HashMap<>();
        Map<ItemType, BigDecimal> ordinaryClosed = new HashMap<>();
        Map<UUID, BigDecimal> baseByItem = new HashMap<>();
        for (EstimateItem item : items) {
            if (item.getUnit() == Unit.PERCENT) {
                continue;
            }
            BigDecimal amount = amountOf(item);
            BigDecimal done = closedByItem.getOrDefault(item.getId(), BigDecimal.ZERO);
            baseByItem.put(item.getId(), amount);
            ordinaryBase.merge(item.getType(), amount, BigDecimal::add);
            ordinaryClosed.merge(item.getType(), done, BigDecimal::add);
        }

        // 2. Percentages of a position (or of a legacy hand-typed sum). They are themselves part of
        //    the base a «% від кошторису» measures, so their closed share is needed before step 3.
        Map<ItemType, BigDecimal> totalBase = new HashMap<>(ordinaryBase);
        Map<ItemType, BigDecimal> totalClosed = new HashMap<>(ordinaryClosed);
        Map<ItemType, BigDecimal> adjustments = new LinkedHashMap<>();
        adjustments.put(ItemType.WORK, BigDecimal.ZERO);
        adjustments.put(ItemType.MATERIAL, BigDecimal.ZERO);
        List<EstimateItem> ofEstimate = new ArrayList<>();
        for (EstimateItem item : items) {
            if (item.getUnit() != Unit.PERCENT) {
                continue;
            }
            if (kindOf(item) == PercentBaseKind.TOTAL) {
                ofEstimate.add(item);
                continue;
            }
            BigDecimal amount = amountOf(item);
            BigDecimal carried = amount.multiply(
                    positionRatio(item, baseByItem, closedByItem, ordinaryBase, ordinaryClosed));
            adjustments.merge(item.getType(), carried, BigDecimal::add);
            totalBase.merge(item.getType(), amount, BigDecimal::add);
            totalClosed.merge(item.getType(), carried, BigDecimal::add);
        }

        // 3. «% від кошторису» — against its own type's subtotal, exactly as EstimateMath measured
        //    it, so a fully closed estimate carries the whole percentage across.
        for (EstimateItem item : ofEstimate) {
            BigDecimal ratio = ratio(totalClosed.get(item.getType()), totalBase.get(item.getType()));
            adjustments.merge(item.getType(), amountOf(item).multiply(ratio), BigDecimal::add);
        }
        return adjustments;
    }

    /**
     * A POSITION percentage travels with its own base line. A base that is detached, deleted or
     * never recorded (a legacy MANUAL line, whose base was a sum typed into the price column) has
     * no line to follow, so it takes the ratio of its type as a whole — the honest answer when all
     * that is known is that the money belongs to this estimate's works or its materials.
     */
    private static BigDecimal positionRatio(EstimateItem item, Map<UUID, BigDecimal> baseByItem,
                                            Map<UUID, BigDecimal> closedByItem,
                                            Map<ItemType, BigDecimal> ordinaryBase,
                                            Map<ItemType, BigDecimal> ordinaryClosed) {
        UUID baseId = item.isBaseDetached() ? null : item.getPercentBaseItemId();
        if (baseId != null && kindOf(item) == PercentBaseKind.POSITION
                && baseByItem.containsKey(baseId)) {
            return ratio(closedByItem.get(baseId), baseByItem.get(baseId));
        }
        return ratio(ordinaryClosed.get(item.getType()), ordinaryBase.get(item.getType()));
    }

    private static BigDecimal ratio(BigDecimal closed, BigDecimal base) {
        if (closed == null || base == null || base.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return closed.divide(base, RATIO_SCALE, ROUNDING);
    }

    private static BigDecimal amountOf(EstimateItem item) {
        return Objects.requireNonNullElse(item.getLineTotal(), BigDecimal.ZERO);
    }

    /** A PERCENT line with no kind recorded is MANUAL — the same reading {@link EstimateMath} uses. */
    private static PercentBaseKind kindOf(EstimateItem item) {
        return item.getPercentBaseKind() == null ? PercentBaseKind.MANUAL : item.getPercentBaseKind();
    }
}
