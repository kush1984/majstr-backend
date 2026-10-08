package com.majstr.backend.service;

import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActItem;
import com.majstr.backend.entity.WorkActLineKind;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.WorkActItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The act's money squares with the contract to the kopeck once every position is closed (B-87).
 *
 * <p>Two roundings used to drift on their own. A position closed over several acts priced each part
 * at {@code price × quantity}: 100 × 145 ₴ over 33.333 / 33.333 / 33.334 is 14 500.01 against a
 * 14 500 contract. And each act rounded its share of a «%» line by itself: +5 % on 10 × 187.50 over
 * 1 + 9 m² carried 9.38 + 84.38 = 93.76 against 93.75. A third of multi-act closures missed by a
 * kopeck, half of them ABOVE the contract.</p>
 *
 * <ul>
 *   <li>The line that closes a position's LAST unit takes exactly what remains: the position's own
 *       total less what signed acts already billed for it.</li>
 *   <li>The adjustments are computed CUMULATIVELY by {@link ActAdjustmentCalculator} — this act's
 *       share is the rounded figure with it, less the rounded figure without it — so the shares
 *       telescope to the estimate's own percentage.</li>
 * </ul>
 *
 * <p>Already-signed acts are never rewritten (owner ruling, 2026-10-08): the cumulative figure is
 * re-derived from what they CLOSED, never from the adjustment rows they carry, so a pre-V141 act
 * signed without one stays as it is and is not «caught up» on the next act.</p>
 */
@Component
@RequiredArgsConstructor
class ActRepricer {

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final WorkActItemRepository itemRepository;
    private final EstimateItemRepository estimateItemRepository;
    private final ActAdjustmentCalculator adjustmentCalculator;

    /**
     * Re-derive the stored act: last-unit totals and the adjustment rows. Run at publish, at the
     * offline signature and when a REJECTED act comes back — the doors where the master's document
     * is (re)issued. Between publish and the portal signature nothing can move it: the one-open-act
     * rule stops any other act signing, and a live act stops the estimate being edited.
     */
    void reprice(WorkAct act) {
        List<WorkActItem> all = itemRepository.findByWorkActIdOrderBySortOrderAscIdAsc(act.getId());
        List<WorkActItem> adjustments = all.stream()
                .filter(i -> i.getLineKind() == WorkActLineKind.ADJUSTMENT).toList();
        List<WorkActItem> lines = all.stream()
                .filter(i -> i.getLineKind() != WorkActLineKind.ADJUSTMENT).toList();
        itemRepository.deleteAll(adjustments);
        itemRepository.flush();
        settleLastUnits(lines, act.getProject().getId());
        int next = lines.stream().mapToInt(WorkActItem::getSortOrder).max().orElse(-1) + 1;
        itemRepository.saveAll(lines);
        itemRepository.saveAll(adjustmentCalculator.adjustmentsFor(act, lines, next));
    }

    /**
     * Give the line that closes a position's last unit exactly what remains of it. A position split
     * over two rows of this act settles on the later row.
     */
    void settleLastUnits(List<WorkActItem> lines, UUID projectId) {
        Map<UUID, List<WorkActItem>> byItem = new LinkedHashMap<>();
        for (WorkActItem line : lines) {
            if (line.getLineKind() == WorkActLineKind.ESTIMATE && line.getEstimateItemId() != null) {
                byItem.computeIfAbsent(line.getEstimateItemId(), k -> new ArrayList<>()).add(line);
            }
        }
        if (byItem.isEmpty()) {
            return;
        }
        Map<UUID, BigDecimal> doneQty = signed(itemRepository.sumSignedQuantitiesByEstimateItem(projectId));
        Map<UUID, BigDecimal> doneTotal = signed(itemRepository.sumSignedLineTotalsByEstimateItem(projectId));
        Map<UUID, EstimateItem> positions = new HashMap<>();
        estimateItemRepository.findAllById(byItem.keySet()).forEach(p -> positions.put(p.getId(), p));
        for (Map.Entry<UUID, List<WorkActItem>> e : byItem.entrySet()) {
            EstimateItem position = positions.get(e.getKey());
            if (position == null || position.getLineTotal() == null) {
                continue;
            }
            List<WorkActItem> rows = e.getValue();
            BigDecimal qty = doneQty.getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal others = doneTotal.getOrDefault(e.getKey(), BigDecimal.ZERO);
            for (WorkActItem row : rows) {
                qty = qty.add(row.getQuantity());
            }
            if (qty.compareTo(position.getQuantity()) != 0) {
                continue; // not the last unit — the ordinary price × quantity stands
            }
            for (WorkActItem row : rows.subList(0, rows.size() - 1)) {
                others = others.add(row.getLineTotal());
            }
            BigDecimal remainder = position.getLineTotal().subtract(others).setScale(MONEY_SCALE, ROUNDING);
            WorkActItem last = rows.getLast();
            // Never more than a kopeck per row away from price × quantity: anything bigger is not
            // rounding but a re-priced position, and the act's frozen price is what the client read.
            if (remainder.subtract(last.getLineTotal()).abs()
                    .compareTo(new BigDecimal("0.01").multiply(BigDecimal.valueOf(rowsBilled(e.getKey(), rows)))) <= 0) {
                last.setLineTotal(remainder);
            }
        }
    }

    /** How many rows have billed this position so far — the most kopecks rounding can have drifted. */
    private int rowsBilled(UUID estimateItemId, List<WorkActItem> rowsOfThisAct) {
        return itemRepository.countSignedLinesForEstimateItem(estimateItemId) + rowsOfThisAct.size();
    }

    private static Map<UUID, BigDecimal> signed(List<Object[]> rows) {
        Map<UUID, BigDecimal> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put((UUID) row[0], (BigDecimal) row[1]);
        }
        return map;
    }
}
