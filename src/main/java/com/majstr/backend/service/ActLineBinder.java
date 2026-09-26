package com.majstr.backend.service;

import com.majstr.backend.dto.WorkActItemsRequest;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateKind;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActItem;
import com.majstr.backend.exception.WorkActValidationException;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.WorkActItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What an act LINE is allowed to be — in one place, because the answer must be the same at the
 * write and at every door the act leaves the master's hands through (review B-56/B-57).
 *
 * <p>An act line is a FROZEN copy, and that freeze is the point: the client signed THOSE words at
 * THAT price a year ago. But a copy is only honest if it was taken from the estimate in the first
 * place — and it was not. {@code PUT /acts/{id}/items} took name, type, unit and unitPrice
 * verbatim from the request and derived only {@code estimateId} server-side, so a line could claim
 * to close «Штукатурка, 250 ₴/м²» while billing 900 ₴/м², or close 200 м² of a 100 м² position.
 * Both raise «Прийнято актами» above «За договором» — the one invariant the whole acts chain
 * exists to keep ({@code ObjectExpenseService.actsAxis}).</p>
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li><b>Linked line ⇒ the estimate owns the words and the price.</b> For a line naming an
 *       {@code estimateItemId}, type/name/category/unit/unitPrice are READ off the estimate item;
 *       the request's own values are ignored, never merely validated. Only the quantity is the
 *       master's to choose — which is exactly what the act editor offers him.</li>
 *   <li><b>A link must point at closeable work.</b> The estimate must be SIGNED, counted in the
 *       economy, belong to THIS object and not be an ADDENDUM (an addendum is what a signed act
 *       WRITES; closing one with another act would bill it twice).</li>
 *   <li><b>Never more than remains.</b> Σ of this request's quantities for one estimate item, plus
 *       what SIGNED acts already closed, may not exceed the estimate line's quantity. The PWA
 *       already offers «винести надлишок окремим рядком» — the excess belongs on an ADDITIONAL
 *       line, where the ADDENDUM will absorb it into «За договором».</li>
 *   <li><b>No «%» line on an act, linked or not (B-57).</b> A percentage is a share of something,
 *       and an act closes a QUANTITY. Worse, it was silently wrong by a factor of 100: the act
 *       line computed {@code unitPrice × quantity} while {@link ActAddendumCreator} rolled the same
 *       row into an estimate where {@link EstimateMath} reads it as a percentage. «Прийнято» then
 *       outgrew «За договором» by ~99 % of the line.</li>
 * </ul>
 *
 * <p>{@link #requireStillValid} re-runs the STRUCTURAL half at publish and at both sign paths — the
 * estimate can be reopened, uncounted or deleted between the save and the signature. It
 * deliberately does NOT re-check prices or wording: the frozen copy is the feature, and an act
 * saved before the master edited the estimate must still sign at the figures the client saw.</p>
 */
@Component
@RequiredArgsConstructor
class ActLineBinder {

    private static final int QUANTITY_SCALE = 3;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final EstimateItemRepository estimateItemRepository;
    private final WorkActItemRepository itemRepository;

    /**
     * The verified estimate items a write must copy its linked lines from, plus the object's
     * already-closed quantities — both come out of the same two queries the write needs anyway
     * ({@code done} is also what {@code cumulative_before} freezes).
     */
    record Bound(Map<UUID, EstimateItem> linked, Map<UUID, BigDecimal> done) {}

    /** Write path: everything above, with the estimate items handed back for field derivation. */
    Bound bind(List<WorkActItemsRequest.Line> lines, UUID projectId) {
        for (WorkActItemsRequest.Line line : lines) {
            requireNotPercent(line.unit());
        }
        Map<UUID, BigDecimal> requested = new LinkedHashMap<>();
        for (WorkActItemsRequest.Line line : lines) {
            if (line.estimateItemId() != null) {
                // Aggregated per estimate item, never checked row by row: two 60 м² lines against a
                // 100 м² position each pass alone and close 120 together.
                requested.merge(line.estimateItemId(),
                        line.quantity().setScale(QUANTITY_SCALE, ROUNDING), BigDecimal::add);
            }
        }
        Map<UUID, BigDecimal> done = signedDone(projectId);
        if (requested.isEmpty()) {
            return new Bound(Map.of(), done);
        }
        Map<UUID, EstimateItem> linked = load(requested.keySet());
        for (Map.Entry<UUID, BigDecimal> e : requested.entrySet()) {
            EstimateItem item = requireCloseable(linked.get(e.getKey()), projectId);
            requireWithinRemaining(item, e.getValue(), done);
        }
        return new Bound(linked, done);
    }

    /** Publish / both sign paths: the structural half only, over the act's already-frozen lines. */
    void requireStillValid(WorkAct act, List<WorkActItem> items) {
        Map<UUID, BigDecimal> requested = new LinkedHashMap<>();
        for (WorkActItem item : items) {
            requireNotPercent(item.getUnit());
            if (item.getEstimateItemId() != null) {
                requested.merge(item.getEstimateItemId(),
                        item.getQuantity().setScale(QUANTITY_SCALE, ROUNDING), BigDecimal::add);
            }
        }
        if (requested.isEmpty()) {
            return;
        }
        UUID projectId = act.getProject().getId();
        Map<UUID, EstimateItem> linked = load(requested.keySet());
        Map<UUID, BigDecimal> done = signedDone(projectId);
        for (Map.Entry<UUID, BigDecimal> e : requested.entrySet()) {
            EstimateItem item = requireCloseable(linked.get(e.getKey()), projectId);
            requireWithinRemaining(item, e.getValue(), done);
        }
    }

    /** Convenience for the doors that have not loaded the lines yet. */
    void requireStillValid(WorkAct act) {
        requireStillValid(act, itemRepository.findByWorkActIdOrderBySortOrderAscIdAsc(act.getId()));
    }

    /** How much of each estimate line the object's SIGNED acts have already closed. */
    Map<UUID, BigDecimal> signedDone(UUID projectId) {
        Map<UUID, BigDecimal> map = new HashMap<>();
        for (Object[] row : itemRepository.sumSignedQuantitiesByEstimateItem(projectId)) {
            map.put((UUID) row[0], ((BigDecimal) row[1]).setScale(QUANTITY_SCALE, ROUNDING));
        }
        return map;
    }

    private Map<UUID, EstimateItem> load(Set<UUID> ids) {
        Map<UUID, EstimateItem> byId = new HashMap<>();
        estimateItemRepository.findAllById(ids).forEach(it -> byId.put(it.getId(), it));
        return byId;
    }

    /**
     * The estimate position must still be one an act may close. A missing item is the same answer
     * as an excluded one on purpose: the caller learns nothing about whose estimate it was (the
     * project pin is what stops another master's item UUID from passing).
     */
    private static EstimateItem requireCloseable(EstimateItem item, UUID projectId) {
        Estimate e = item == null ? null : item.getEstimate();
        if (e == null || e.getStatus() != EstimateStatus.SIGNED || !e.isCountInEconomy()
                || !e.getProject().getId().equals(projectId)) {
            throw new WorkActValidationException(
                    "error.work-act.estimate-excluded", "WORK_ACT_ESTIMATE_EXCLUDED");
        }
        if (e.getKind() == EstimateKind.ADDENDUM) {
            throw new WorkActValidationException(
                    "error.work-act.addendum-line", "WORK_ACT_ADDENDUM_LINE");
        }
        requireNotPercent(item.getUnit());
        return item;
    }

    private static void requireWithinRemaining(
            EstimateItem item, BigDecimal requested, Map<UUID, BigDecimal> done) {
        BigDecimal remaining = item.getQuantity()
                .subtract(done.getOrDefault(item.getId(), BigDecimal.ZERO))
                .setScale(QUANTITY_SCALE, ROUNDING);
        if (requested.compareTo(remaining) > 0) {
            throw new WorkActValidationException(
                    "error.work-act.over-estimate", "WORK_ACT_OVER_ESTIMATE");
        }
    }

    private static void requireNotPercent(Unit unit) {
        if (unit == Unit.PERCENT) {
            throw new WorkActValidationException(
                    "error.work-act.percent-line", "WORK_ACT_PERCENT_LINE");
        }
    }
}
