package com.majstr.backend.service;

import com.lowagie.text.DocumentException;
import com.majstr.backend.dto.CalculatedMaterialRow;
import com.majstr.backend.dto.ShoppingListItemRequest;
import com.majstr.backend.dto.ShoppingListItemResponse;
import com.majstr.backend.dto.ShoppingListItemUpdateRequest;
import com.majstr.backend.dto.ShoppingListResponse;
import com.majstr.backend.dto.ShoppingListSummaryResponse;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ShoppingList;
import com.majstr.backend.entity.ShoppingListItem;
import com.majstr.backend.entity.ShoppingListItemSource;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ShoppingListItemRepository;
import com.majstr.backend.repository.ShoppingListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The object's shopping list: one per object, because the master drives to the builders' merchant
 * once and buys for the OBJECT, while an object may carry several estimates.
 *
 * <p><b>This service never writes money.</b> No {@code ObjectExpense}, no earnings, no amount on a
 * row. The single bridge is the receipt button, which goes to the existing receipt import — and a
 * receipt is also where the real shop price comes from, the path V81 called the right one.</p>
 *
 * <p>Two rules govern {@link #applyCalculated}, and both fail SILENTLY if broken, which is why each
 * has its own test:</p>
 * <ol>
 *   <li><b>A recalculation replaces its own contribution, it never adds to it.</b> We are the ones
 *       offering the master a recalculation after he fixes a typo; if a re-run added, he would buy
 *       twice the material. The contribution is identified by {@code (source, sourceEstimateId)}
 *       and nothing outside it is touched — not another estimate's rows, not hand-written ones.</li>
 *   <li><b>A settled row is never modified.</b> Bought or cleared, its quantity and its flag stay
 *       exactly as they are. A larger new figure becomes a SEPARATE open row carrying only the
 *       difference, because "bought 12" quietly turning into "18" answers neither "did I buy it?"
 *       nor "how much is still missing?".</li>
 * </ol>
 *
 * <p>A row the master edited by hand is left alone for the same reason: his number wins. What the
 * recalculation WOULD have written is parked on the row as {@code suggestedQuantity} (V128) and
 * offered with one tap — shown, never swallowed, and never applied behind his back.</p>
 */
@Service
@RequiredArgsConstructor
public class ShoppingListService {

    private final ShoppingListRepository listRepository;
    private final ShoppingListItemRepository itemRepository;
    private final EstimateRepository estimateRepository;
    private final ProjectService projectService;
    private final ShoppingListPdfService pdfService;

    @Transactional(readOnly = true)
    public ShoppingListResponse get(UUID projectId, UUID ownerId) {
        Project project = projectService.loadOwned(projectId, ownerId);
        ShoppingList list = listRepository.findByProjectId(projectId).orElse(null);
        if (list == null) {
            return ShoppingListResponse.empty(projectId, project.getName());
        }
        return render(list, project);
    }

    /**
     * The same list as a PDF, for «поділитись списком з клієнтом» — half the time the client buys
     * the material himself, and what the master sends him then should look like a document, not a
     * paste from the share sheet.
     *
     * <p>Assembled here rather than in the controller because {@code Project.owner} is LAZY and
     * {@code open-in-view} is off: the model has to be built while the transaction is still open.</p>
     */
    @Transactional(readOnly = true)
    public byte[] renderPdf(UUID projectId, UUID ownerId) throws DocumentException {
        Project project = projectService.loadOwned(projectId, ownerId);
        ShoppingList list = listRepository.findByProjectId(projectId).orElse(null);
        ShoppingListResponse rendered = list == null
                ? ShoppingListResponse.empty(projectId, project.getName())
                : render(list, project);
        return pdfService.render(
                new ShoppingListPdfService.PdfModel(project.getOwner(), project, rendered));
    }

    /** The home-screen card: only live lists that still have something left to buy. */
    @Transactional(readOnly = true)
    public List<ShoppingListSummaryResponse> summaries(UUID ownerId) {
        return listRepository.summaryRows(ownerId).stream()
                .filter(row -> row.getTotalCount() > row.getBoughtCount())
                .map(row -> new ShoppingListSummaryResponse(
                        row.getProjectId(),
                        row.getProjectName(),
                        (int) row.getTotalCount(),
                        (int) row.getBoughtCount()))
                .toList();
    }

    /**
     * Add a hand-written row, optionally under a CLIENT-PROVIDED id so a replayed offline create
     * returns the existing row instead of a second one. Hand-written rows are never merged into
     * each other — see the {@code ux_shopping_list_item_open} comment in V126.
     */
    @Transactional
    public ShoppingListItemResponse addManual(UUID projectId, UUID ownerId,
                                              ShoppingListItemRequest req, UUID requestedId) {
        Project project = projectService.loadOwned(projectId, ownerId);
        ShoppingList list = getOrCreate(project);
        UUID id = requestedId;
        if (id != null) {
            // Scoped to THIS list, so the replay lookup is as owner-bound as the create it replays.
            // An unscoped findById answered about somebody else's row: it either leaked that row or
            // — for a foreign id — threw 403 at a master whose own create had simply never landed.
            // A miss now means "not mine, not here", which is exactly the create path below.
            ShoppingListItem existing = itemRepository
                    .findByIdAndShoppingListId(id, list.getId()).orElse(null);
            if (existing != null) {
                return ShoppingListItemResponse.from(existing, false); // idempotent replay
            }
            if (itemRepository.existsById(id)) {
                // Taken by a row on ANOTHER list — and the client id is this table's PRIMARY KEY, so
                // `save()` with it set is a MERGE: reusing it would overwrite that row and move it
                // onto this list. A header must never be able to write somebody else's row, so the
                // id the client offered is dropped and the server authors its own. Only a colliding
                // or forged uuid reaches here; the PWA mints a fresh one per queued create.
                id = null;
            }
        }
        ShoppingListItem item = ShoppingListItem.builder()
                .id(id)
                .shoppingListId(list.getId())
                .materialId(req.materialId())
                .name(req.name().trim())
                .unit(req.unit())
                .quantity(req.quantity())
                .source(ShoppingListItemSource.MANUAL)
                .note(trimToNull(req.note()))
                .sortOrder(itemRepository.maxSortOrder(list.getId()) + 1)
                .build();
        return ShoppingListItemResponse.from(itemRepository.save(item), false);
    }

    @Transactional
    public ShoppingListItemResponse update(UUID projectId, UUID ownerId, UUID itemId,
                                           ShoppingListItemUpdateRequest req) {
        ShoppingListItem item = loadItem(projectId, ownerId, itemId);
        if (req.quantity() != null && req.quantity().compareTo(item.getQuantity()) != 0) {
            item.setQuantity(req.quantity());
            // From here on the master owns this number: a recalculation reports the difference
            // instead of overwriting it. A newer figure of his own answers any parked offer.
            item.setEdited(true);
            item.setSuggestedQuantity(null);
        }
        applySuggestion(item, req.suggestion());
        if (req.note() != null) {
            item.setNote(trimToNull(req.note()));
        }
        if (req.bought() != null) {
            applyBought(item, req.bought());
        }
        return rendered(item);
    }

    /** The one action the master performs in the shop, so it must work with no network at all. */
    @Transactional
    public ShoppingListItemResponse setBought(UUID projectId, UUID ownerId, UUID itemId, boolean bought) {
        ShoppingListItem item = loadItem(projectId, ownerId, itemId);
        applyBought(item, bought);
        return rendered(item);
    }

    /**
     * Deleting a SETTLED row HIDES it ({@code cleared_at}) instead of removing it — the same rule,
     * and the same reason, as {@link #clearBought}. A bought row's quantity is what {@code covered}
     * counts; drop the row and the demand returns in full, so the next recalculation re-adds the
     * material as unbought and the master buys it a second time. A hidden row stays settled, which
     * is exactly what stops that, and it costs him nothing — hidden and deleted look alike on the
     * screen.
     *
     * <p>Deliberately not a 409. This swipe replays from the offline outbox hours later, where a
     * refusal is unactionable (the argument that narrowed B-28). The honest «I did not buy it»
     * gesture still deletes: un-tick first — {@code applyBought} clears BOTH flags — then delete.</p>
     */
    @Transactional
    public void delete(UUID projectId, UUID ownerId, UUID itemId) {
        projectService.loadOwned(projectId, ownerId);
        ShoppingList list = listRepository.findByProjectId(projectId).orElse(null);
        if (list == null) {
            return; // idempotent, like every other offline-replayable delete
        }
        itemRepository.findByIdAndShoppingListId(itemId, list.getId()).ifPresent(item -> {
            if (!item.settled()) {
                itemRepository.delete(item);
            } else if (item.getClearedAt() == null) {
                item.setClearedAt(Instant.now());
            }
        });
    }

    /**
     * Clearing the bought rows HIDES them, it does not delete them. Deleting here costs the master
     * money: the next recalculation would re-add the material as unbought and he would buy it a
     * second time. A hidden row still counts as settled, which is exactly what blocks that.
     */
    @Transactional
    public ShoppingListResponse clearBought(UUID projectId, UUID ownerId) {
        Project project = projectService.loadOwned(projectId, ownerId);
        ShoppingList list = listRepository.findByProjectId(projectId).orElse(null);
        if (list == null) {
            return ShoppingListResponse.empty(projectId, project.getName());
        }
        Instant now = Instant.now();
        for (ShoppingListItem item : itemRepository.findByShoppingListIdAndBoughtTrueAndClearedAtIsNull(list.getId())) {
            item.setClearedAt(now);
        }
        return render(list, project);
    }

    /**
     * Put the result of a calculation of ONE estimate onto the object's list, obeying both rules in
     * the class javadoc. Returns the whole list, because a recalculation can change several rows at
     * once and the caller should not have to guess which.
     */
    @Transactional
    public ShoppingListResponse applyCalculated(UUID projectId, UUID ownerId, UUID estimateId,
                                                List<CalculatedMaterialRow> rows) {
        Project project = projectService.loadOwned(projectId, ownerId);
        ShoppingList list = getOrCreate(project);

        Map<String, CalculatedMaterialRow> target = mergeInput(rows);

        List<ShoppingListItem> contribution = itemRepository
                .findByShoppingListIdAndSourceAndSourceEstimateId(
                        list.getId(), ShoppingListItemSource.CALCULATOR, estimateId);

        Map<String, List<ShoppingListItem>> existing = new LinkedHashMap<>();
        for (ShoppingListItem item : contribution) {
            existing.computeIfAbsent(item.dedupKey(), k -> new ArrayList<>()).add(item);
        }

        int nextSortOrder = itemRepository.maxSortOrder(list.getId()) + 1;

        for (Map.Entry<String, CalculatedMaterialRow> entry : target.entrySet()) {
            List<ShoppingListItem> rowsForKey = existing.getOrDefault(entry.getKey(), List.of());
            ShoppingListItem open = openRow(rowsForKey);
            BigDecimal covered = coveredQuantity(rowsForKey);
            BigDecimal remaining = entry.getValue().quantity().subtract(covered);
            if (open != null && open.isEdited()) {
                // His number, not ours — but park ours beside it. Dropping it silently made the
                // one case where our arithmetic had improved look exactly like the case where it
                // had not, and only he can tell those apart.
                open.setSuggestedQuantity(differs(remaining, open.getQuantity()) ? remaining : null);
                continue;
            }
            if (remaining.signum() <= 0) {
                if (open != null) {
                    // Deleted even when it carries a note, unlike the branch below, and that
                    // asymmetry is deliberate: his own settled purchases of THIS material already
                    // cover the demand, so what is left open is a top-up he no longer needs. The
                    // schema forbids an open row at 0 (shopping_list_item_quantity_check), so the
                    // alternative is a stale figure standing in a shop list — that costs him money,
                    // a lost note does not.
                    itemRepository.delete(open);
                }
                continue;
            }
            if (open != null) {
                open.setQuantity(remaining);
                open.setEstimateItemId(entry.getValue().estimateItemId());
            } else {
                CalculatedMaterialRow row = entry.getValue();
                itemRepository.save(ShoppingListItem.builder()
                        .shoppingListId(list.getId())
                        .materialId(row.materialId())
                        .name(row.name())
                        .unit(row.unit())
                        .quantity(remaining)
                        .source(ShoppingListItemSource.CALCULATOR)
                        .sourceEstimateId(estimateId)
                        .estimateItemId(row.estimateItemId())
                        .sortOrder(nextSortOrder++)
                        .build());
            }
        }

        // Gone from the new calculation: drop only what is still open and untouched. A settled or
        // hand-authored row records something that really happened and outlives the estimate line.
        for (Map.Entry<String, List<ShoppingListItem>> entry : existing.entrySet()) {
            if (target.containsKey(entry.getKey())) {
                continue;
            }
            ShoppingListItem open = openRow(entry.getValue());
            if (open == null) {
                continue;
            }
            if (open.authoredByMaster()) {
                open.setSuggestedQuantity(null); // nothing left to offer against
            } else {
                itemRepository.delete(open);
            }
        }

        return render(list, project);
    }

    /** Archive/unarchive from the object-status hook — see {@code ProjectService.updateStatus}. */
    @Transactional
    public void setArchived(UUID projectId, boolean archived) {
        listRepository.updateArchivedAt(projectId, archived ? Instant.now() : null);
    }

    // ---------------------------------------------------------------------------------------

    /** Several calculated lines can want the same material; they are one row, summed up front. */
    private Map<String, CalculatedMaterialRow> mergeInput(List<CalculatedMaterialRow> rows) {
        Map<String, CalculatedMaterialRow> merged = new LinkedHashMap<>();
        for (CalculatedMaterialRow row : rows) {
            String key = ShoppingListItem.dedupKey(row.materialId(), row.name(), row.unit());
            merged.merge(key, row, (a, b) -> new CalculatedMaterialRow(
                    a.materialId(), a.name(), a.unit(), a.quantity().add(b.quantity()), a.estimateItemId()));
        }
        return merged;
    }

    /** At most one, guaranteed by {@code ux_shopping_list_item_open}. */
    private ShoppingListItem openRow(List<ShoppingListItem> rows) {
        return rows.stream().filter(i -> !i.settled()).findFirst().orElse(null);
    }

    /**
     * What the master has already dealt with for this material: bought and cleared rows both count.
     * A cleared row still counting is the whole reason clearing cannot delete.
     */
    private BigDecimal coveredQuantity(List<ShoppingListItem> rows) {
        return rows.stream()
                .filter(ShoppingListItem::settled)
                .map(ShoppingListItem::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The master's answer to a parked figure. Applied to a row that no longer carries one it does
     * nothing at all — this screen queues its writes offline, so by the time a tap replays the
     * suggestion may already have been answered from another device.
     */
    private void applySuggestion(ShoppingListItem item, ShoppingListItemUpdateRequest.Suggestion answer) {
        if (answer == null || item.getSuggestedQuantity() == null) {
            return;
        }
        if (answer == ShoppingListItemUpdateRequest.Suggestion.ACCEPT) {
            item.setQuantity(item.getSuggestedQuantity());
            // He handed the row back to the calculator, so the next run may write it again.
            item.setEdited(false);
        }
        item.setSuggestedQuantity(null);
    }

    private boolean differs(BigDecimal a, BigDecimal b) {
        return a.signum() > 0 && a.compareTo(b) != 0;
    }

    /**
     * A row that carries only the DIFFERENCE, because something for the same material is already
     * bought or cleared. Saying so is what separates «Шпаклівка · 1 мішок» read as a mistake from
     * the same row read as «ще один» — the split itself is rule 2 in the class javadoc.
     *
     * <p>Derived on the read path, never stored: it is a statement about the row's NEIGHBOURS, and
     * a neighbour can be bought at any time. Ordered by {@code sortOrder}, so only the later row is
     * labelled — a delta row is always appended after the row it tops up.</p>
     */
    private boolean topUp(ShoppingListItem item, List<ShoppingListItem> siblings) {
        if (item.getSource() != ShoppingListItemSource.CALCULATOR) {
            return false;
        }
        String key = item.dedupKey();
        return siblings.stream().anyMatch(other -> other.settled()
                && other.getSortOrder() < item.getSortOrder()
                && other.dedupKey().equals(key));
    }

    /** One row's answer, with the sibling context {@link #topUp} needs. */
    private ShoppingListItemResponse rendered(ShoppingListItem item) {
        if (item.getSource() != ShoppingListItemSource.CALCULATOR) {
            return ShoppingListItemResponse.from(item, false);
        }
        return ShoppingListItemResponse.from(item, topUp(item,
                itemRepository.findByShoppingListIdOrderBySortOrderAscCreatedAtAsc(item.getShoppingListId())));
    }

    private void applyBought(ShoppingListItem item, boolean bought) {
        if (!bought && item.settled()) {
            mergeOpenSibling(item);
        }
        item.setBought(bought);
        item.setBoughtAt(bought ? Instant.now() : null);
        if (!bought) {
            // Un-buying has to UNSETTLE the row, and `cleared_at` settles it just as much as
            // `bought` does. Left standing, the row stayed invisible while its quantity kept
            // counting as covered — so the master un-ticked a material and the next recalculation
            // still refused to ask for it. It also dodged ux_shopping_list_item_open, which is why
            // the merge above has to run for a cleared row as well.
            item.setClearedAt(null);
        }
    }

    /**
     * Un-ticking a row whose top-up sibling is still open MERGES the two.
     * {@code ux_shopping_list_item_open} admits ONE open row per
     * (list, source, source_estimate_id, dedup_key), so the second open row is a constraint
     * violation — and the master, whose tap only meant «я його все ж не купив», is told his list
     * did not save. The row he un-ticked survives (it is the earlier one, the original) and takes
     * the sibling's quantity: together they are the whole demand again, which is exactly what the
     * top-up existed to complete.
     *
     * <p>MANUAL rows are outside that index by design (V126), so two open ones are legal and
     * nothing is merged — a row the master typed himself is never folded into another.</p>
     */
    private void mergeOpenSibling(ShoppingListItem item) {
        if (item.getSource() == ShoppingListItemSource.MANUAL) {
            return;
        }
        String key = item.dedupKey();
        for (ShoppingListItem other : itemRepository
                .findByShoppingListIdOrderBySortOrderAscCreatedAtAsc(item.getShoppingListId())) {
            if (other.getId().equals(item.getId()) || other.settled()
                    || other.getSource() != item.getSource()
                    || !Objects.equals(other.getSourceEstimateId(), item.getSourceEstimateId())
                    || !other.dedupKey().equals(key)) {
                continue;
            }
            // A bulk delete, so the row is gone from the database before this row's own update is
            // flushed: Hibernate orders entity updates ahead of entity deletes, and the update is
            // the one that would collide.
            itemRepository.deleteRow(other.getId());
            item.setQuantity(item.getQuantity().add(other.getQuantity()));
            // A hand-typed figure is now inside the sum, so a recalculation must keep reporting the
            // difference instead of overwriting it.
            item.setEdited(item.isEdited() || other.isEdited());
            // Any parked offer was computed against the quantity that just changed.
            item.setSuggestedQuantity(null);
        }
    }

    private ShoppingList getOrCreate(Project project) {
        return listRepository.findByProjectId(project.getId())
                .orElseGet(() -> listRepository.save(ShoppingList.builder()
                        .projectId(project.getId())
                        .build()));
    }

    private ShoppingListItem loadItem(UUID projectId, UUID ownerId, UUID itemId) {
        projectService.loadOwned(projectId, ownerId);
        ShoppingList list = listRepository.findByProjectId(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Shopping list not found"));
        return itemRepository.findByIdAndShoppingListId(itemId, list.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Shopping list item not found"));
    }

    private ShoppingListResponse render(ShoppingList list, Project project) {
        // Everything, not just the visible rows: a CLEARED row is invisible but still settled, and
        // it is exactly what makes the row beside it a top-up.
        List<ShoppingListItem> all = itemRepository
                .findByShoppingListIdOrderBySortOrderAscCreatedAtAsc(list.getId());
        List<ShoppingListItem> visible = all.stream().filter(i -> i.getClearedAt() == null).toList();
        int bought = (int) visible.stream().filter(ShoppingListItem::isBought).count();
        return new ShoppingListResponse(
                list.getId(),
                project.getId(),
                project.getName(),
                list.getArchivedAt(),
                visible.size(),
                bought,
                unsignedSource(visible),
                visible.stream().map(i -> ShoppingListItemResponse.from(i, topUp(i, all))).toList());
    }

    /**
     * Whether any visible row was calculated from an estimate that is not signed yet. It only ever
     * produces a sentence on the screen — the master is told his quantities can still move, and
     * decides for himself whether to buy now.
     */
    private boolean unsignedSource(List<ShoppingListItem> visible) {
        List<UUID> ids = visible.stream()
                .map(ShoppingListItem::getSourceEstimateId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return !ids.isEmpty()
                && estimateRepository.countByIdInAndStatusNot(ids, EstimateStatus.SIGNED) > 0;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
