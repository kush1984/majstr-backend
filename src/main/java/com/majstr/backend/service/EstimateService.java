package com.majstr.backend.service;

import com.majstr.backend.dto.AddCatalogItemsBatchRequest;
import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.EstimateItemFromCatalogRequest;
import com.majstr.backend.dto.EstimateItemRequest;
import com.majstr.backend.dto.EstimateItemResponse;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.EstimateSummary;
import com.majstr.backend.dto.EstimateUpdateRequest;
import com.lowagie.text.DocumentException;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.MeasurementRefs;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.service.measurement.MeasurementService;
import com.majstr.backend.exception.EmailNotVerifiedException;
import com.majstr.backend.exception.EstimateSignedException;
import com.majstr.backend.exception.InvalidEstimateStatusException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.LimitService;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EstimateService {

    /** Money is rounded to two decimal places (kopiykas) using HALF_UP. */
    static final int MONEY_SCALE = 2;
    /** Quantity keeps three decimals (matches the estimate_items column scale). */
    static final int QUANTITY_SCALE = 3;
    static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    private final EstimateRepository estimateRepository;
    private final EstimateItemRepository itemRepository;
    private final ProjectService projectService;
    private final ProjectRepository projectRepository;
    private final CatalogService catalogService;
    private final EstimatePdfService pdfService;
    private final LimitService limitService;
    private final MeasurementService measurementService;

    // ---- estimates ---------------------------------------------------------

    @Transactional
    public EstimateResponse createForProject(UUID projectId, EstimateCreateRequest req, UUID ownerId) {
        return createForProject(projectId, req, ownerId, null);
    }

    /**
     * Create an estimate, optionally with a CLIENT-PROVIDED id (offline authoring). The id makes it
     * idempotent — a replayed offline create returns the existing estimate (no duplicate, no second
     * hit on the FREE cap or the churn counter); a foreign id (another project) is rejected. The
     * idempotency check runs BEFORE the limit check.
     */
    @Transactional
    public EstimateResponse createForProject(UUID projectId, EstimateCreateRequest req, UUID ownerId, UUID requestedId) {
        Project project = projectService.loadOwned(projectId, ownerId);
        if (requestedId != null) {
            var existing = estimateRepository.findById(requestedId);
            if (existing.isPresent()) {
                Estimate e = existing.get();
                if (!e.getProject().getId().equals(projectId)) {
                    throw new AccessDeniedException("Estimate belongs to a different project");
                }
                return toResponse(e, itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(e.getId()));
            }
        }
        // FREE caps estimates per project (closes the unlimited-drafts hole).
        limitService.requireCanAddEstimate(ownerId, projectId);
        Estimate estimate = Estimate.builder()
                .id(requestedId)
                .project(project)
                .name(normalize(req.name()))
                .validUntil(req.validUntil())
                .notes(normalize(req.notes()))
                .build();
        Estimate saved = estimateRepository.save(estimate);
        projectRepository.incrementEstimatesCreated(projectId); // lifetime churn counter
        return toResponse(saved, List.of());
    }

    /**
     * Creates an estimate on an object from an LLM-extracted import (Excel/photo).
     * Respects the FREE per-project estimate cap and ownership like a normal create,
     * bumps the lifetime churn counter, and persists the extracted items in order.
     * The deposit (завдаток) is carried onto the estimate; money is scaled HALF_UP.
     * The caller ({@code EstimateImportService}) has already gated the feature.
     */
    @Transactional
    public EstimateResponse createFromImport(UUID projectId, ImportEstimateData data, UUID ownerId) {
        Project project = projectService.loadOwned(projectId, ownerId);
        limitService.requireCanAddEstimate(ownerId, projectId);
        Estimate estimate = Estimate.builder()
                .project(project)
                .name(normalize(data.name()))
                .depositAmount(data.depositAmount() == null
                        ? null
                        : data.depositAmount().setScale(MONEY_SCALE, MONEY_ROUNDING))
                .build();
        Estimate saved = estimateRepository.save(estimate);
        projectRepository.incrementEstimatesCreated(projectId); // lifetime churn counter

        List<EstimateItem> items = new ArrayList<>();
        int sortOrder = 0;
        for (ImportEstimateData.ImportItem in : data.items()) {
            items.add(EstimateItem.builder()
                    .estimate(saved)
                    .type(in.type())
                    .name(in.name().trim())
                    .category(CatalogService.normalizeCategory(in.category()))
                    .unit(in.unit())
                    .quantity(in.quantity().setScale(QUANTITY_SCALE, MONEY_ROUNDING))
                    .unitPrice(in.unitPrice().setScale(MONEY_SCALE, MONEY_ROUNDING))
                    .sortOrder(sortOrder++)
                    .build());
        }
        itemRepository.saveAll(items);
        return toResponse(saved, itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(saved.getId()));
    }

    /**
     * Folds several of the object's estimates into one new DRAFT estimate. Every line
     * item from the picked estimates is copied in order (works + materials) — a plain
     * concat, no dedup: the master tidies quantities in the editor, and we never guess
     * which price to keep when the same material cost differently across sources. The
     * copies are independent (measurement selection is NOT carried over). Ownership is
     * checked on the target project and on each source estimate; the FREE per-project
     * estimate cap applies like any create.
     */
    @Transactional
    public EstimateResponse consolidate(UUID projectId, String name, List<UUID> estimateIds, UUID ownerId) {
        Project project = projectService.loadOwned(projectId, ownerId);
        limitService.requireCanAddEstimate(ownerId, projectId);

        Estimate consolidated = estimateRepository.save(Estimate.builder()
                .project(project)
                .name(normalize(name) == null ? "Зведений кошторис" : normalize(name))
                // Everything counts in the economy by default (true) — but the
                // consolidated estimate must NOT, or it would double-count its sources
                // (which stay counted). So this rollup is the one excluded.
                .countInEconomy(false)
                .build());
        projectRepository.incrementEstimatesCreated(projectId); // lifetime churn counter

        List<EstimateItem> copies = new ArrayList<>();
        int sortOrder = 0;
        for (UUID sourceId : estimateIds) {
            Estimate source = loadOwned(sourceId, ownerId);
            if (!source.getProject().getId().equals(projectId)) {
                throw new AccessDeniedException("Estimate does not belong to project " + projectId);
            }
            // Sources keep counting (the rollup above is the excluded one instead).
            for (EstimateItem item : itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(sourceId)) {
                copies.add(EstimateItem.builder()
                        .estimate(consolidated)
                        .type(item.getType())
                        .name(item.getName())
                        .category(item.getCategory())
                        .unit(item.getUnit())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .sortOrder(sortOrder++)
                        .build());
            }
        }
        itemRepository.saveAll(copies);
        return toResponse(consolidated, itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(consolidated.getId()));
    }

    @Transactional(readOnly = true)
    public List<EstimateSummary> listForProject(UUID projectId, UUID ownerId) {
        projectService.loadOwned(projectId, ownerId);
        return estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(EstimateSummary::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public EstimateResponse get(UUID estimateId, UUID ownerId) {
        Estimate estimate = loadOwned(estimateId, ownerId);
        List<EstimateItem> items = itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId);
        return toResponse(estimate, items);
    }

    @Transactional
    public EstimateResponse update(UUID estimateId, EstimateUpdateRequest req, UUID ownerId) {
        Estimate estimate = loadOwned(estimateId, ownerId);
        requireNotSigned(estimate);
        if (req.status() == EstimateStatus.SIGNED) {
            // Message is a bundle key, resolved by GlobalExceptionHandler.
            throw new InvalidEstimateStatusException("error.estimate.manual-sign");
        }
        estimate.setStatus(req.status());
        // Marking it REJECTED means the client turned the deal down, so it stops being
        // income — clear the flag with the status. The income queries already exclude
        // REJECTED, and leaving the flag set would show a ticked "count in economy" box
        // for an estimate that is not, in fact, counted.
        if (req.status() == EstimateStatus.REJECTED) {
            estimate.setCountInEconomy(false);
        }
        estimate.setName(normalize(req.name()));
        estimate.setValidUntil(req.validUntil());
        estimate.setNotes(normalize(req.notes()));
        estimate.setDepositAmount(req.depositAmount() == null
                ? null
                : req.depositAmount().setScale(MONEY_SCALE, MONEY_ROUNDING));
        List<EstimateItem> items = itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId);
        return toResponse(estimate, items);
    }

    /**
     * Deleting a SIGNED estimate is forbidden — it's legally significant (the
     * client agreed to it and work is underway). To remove or change a signed
     * estimate, {@link #reopen} it first (owner-only), which returns it to DRAFT.
     * DRAFT/SENT delete freely.
     */
    @Transactional
    public void delete(UUID estimateId, UUID ownerId) {
        Estimate estimate = loadOwned(estimateId, ownerId);
        if (estimate.getStatus() == EstimateStatus.SIGNED) {
            throw new EstimateSignedException();
        }
        UUID projectId = estimate.getProject().getId();
        estimateRepository.delete(estimate);
        projectRepository.incrementEstimatesDeleted(projectId); // lifetime churn counter
    }

    /**
     * Reopens a SIGNED estimate for edits — <b>owner only</b> (the public portal
     * has no path to this). The signature is cleared and the status returns to
     * DRAFT, so the contractor can revise items and the client must sign again
     * (transparency: the client re-approves the actual current estimate). The
     * reopen is stamped (reopenedAt/reopenedBy) for audit. The project's own
     * status is left as-is — work already started, it just gets a corrected
     * estimate. Only valid on a SIGNED estimate (else 400).
     */
    @Transactional
    public EstimateResponse reopen(UUID estimateId, UUID ownerId) {
        Estimate estimate = loadOwned(estimateId, ownerId);
        if (estimate.getStatus() != EstimateStatus.SIGNED) {
            throw new InvalidEstimateStatusException("error.estimate.not-signed-reopen");
        }
        estimate.setStatus(EstimateStatus.DRAFT);
        estimate.setSignedAt(null);
        estimate.setSignerName(null);
        estimate.setSignerPhone(null);
        estimate.setSignerIp(null);
        estimate.setReopenedAt(Instant.now());
        estimate.setReopenedBy(ownerId);
        List<EstimateItem> items = itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId);
        return toResponse(estimate, items);
    }

    /**
     * Toggle whether this estimate counts toward the object's economy (income). Owner-only;
     * works in any status (you can flag a SIGNED deal or un-flag a superseded variant).
     */
    @Transactional
    public EstimateResponse setCountInEconomy(UUID estimateId, boolean value, UUID ownerId) {
        Estimate estimate = loadOwned(estimateId, ownerId);
        estimate.setCountInEconomy(value);
        return toResponse(estimate, itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId));
    }

    @Transactional(readOnly = true)
    public byte[] renderPdf(UUID estimateId, UUID ownerId) throws IOException, DocumentException {
        Estimate estimate = loadOwned(estimateId, ownerId);
        // The PDF is a client-facing deliverable — gate it behind a verified email
        // (even on FREE) so a throwaway account can't churn out finished estimates.
        if (!estimate.getProject().getOwner().isEmailVerified()) {
            throw new EmailNotVerifiedException("error.email-not-verified");
        }
        return renderPdf(estimate);
    }

    /**
     * Used by both authenticated and public flows. Caller has already
     * validated access (ownership or share-link token).
     */
    @Transactional(readOnly = true)
    public byte[] renderPdf(Estimate estimate) throws IOException, DocumentException {
        Project project = estimate.getProject();
        List<EstimateItem> items = itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimate.getId());
        return pdfService.render(new EstimatePdfService.PdfModel(
                project.getOwner(),
                project,
                project.getClient(),
                estimate,
                items
        ));
    }

    // ---- items -------------------------------------------------------------

    @Transactional
    public EstimateItemResponse addItem(UUID estimateId, EstimateItemRequest req, UUID ownerId) {
        return addItem(estimateId, req, ownerId, null);
    }

    /**
     * Add a line item, optionally with a CLIENT-PROVIDED id (offline authoring). The id makes the
     * add idempotent: a replayed offline add returns the existing item instead of duplicating. An
     * id that already belongs to a DIFFERENT estimate is rejected (never cross-links).
     */
    @Transactional
    public EstimateItemResponse addItem(UUID estimateId, EstimateItemRequest req, UUID ownerId, UUID requestedId) {
        Estimate estimate = loadOwned(estimateId, ownerId);
        if (requestedId != null) {
            var existing = itemRepository.findById(requestedId);
            if (existing.isPresent()) {
                if (!existing.get().getEstimate().getId().equals(estimateId)) {
                    throw new AccessDeniedException("Item belongs to a different estimate");
                }
                return EstimateItemResponse.from(existing.get()); // idempotent replay
            }
        }
        requireNotSigned(estimate);
        Resolved r = resolveQuantity(estimate, req);
        EstimateItem item = EstimateItem.builder()
                .id(requestedId)
                .estimate(estimate)
                .type(req.type())
                .name(req.name().trim())
                .category(CatalogService.normalizeCategory(req.category()))
                .unit(req.unit())
                .quantity(r.quantity())
                .unitPrice(req.unitPrice())
                .measurementRefs(r.refs())
                .quantityManual(r.manual())
                .sortOrder(req.sortOrder() == null ? 0 : req.sortOrder())
                .build();
        return EstimateItemResponse.from(itemRepository.save(item));
    }

    /**
     * Resolves the line's quantity + measurement selection. When the line pulled from
     * measurements (refs present, not hand-edited), the quantity is <b>recomputed on the
     * server</b> from the selected elements — the client's number is never trusted, and
     * each element's unit is checked against the line's. Otherwise the sent quantity stands
     * and the selection is kept as memory (with the manual flag).
     */
    private record Resolved(BigDecimal quantity, String refs, boolean manual) {}

    private Resolved resolveQuantity(Estimate estimate, EstimateItemRequest req) {
        String refs = MeasurementRefs.format(req.measurementRefs());
        boolean hasRefs = req.measurementRefs() != null && !req.measurementRefs().isEmpty();
        if (hasRefs && !req.quantityManual()) {
            BigDecimal quantity = measurementService.sumForRefs(
                    estimate.getProject().getId(), req.measurementRefs(), req.unit());
            return new Resolved(quantity, refs, false);
        }
        return new Resolved(req.quantity(), refs, req.quantityManual());
    }

    @Transactional
    public EstimateItemResponse addItemFromCatalog(UUID estimateId,
                                                   UUID catalogItemId,
                                                   EstimateItemFromCatalogRequest req,
                                                   UUID ownerId) {
        Estimate estimate = loadOwned(estimateId, ownerId);
        requireNotSigned(estimate);
        CatalogItem source = catalogService.loadOwned(catalogItemId, ownerId);
        // Copy the category from the catalog item so the estimate can group too.
        EstimateItem item = EstimateItem.builder()
                .estimate(estimate)
                .type(source.getType())
                .name(source.getName())
                .category(source.getCategory())
                .unit(source.getUnit())
                .quantity(req.quantity())
                .unitPrice(source.getDefaultPrice())
                .sortOrder(req.sortOrder() == null ? 0 : req.sortOrder())
                .build();
        return EstimateItemResponse.from(itemRepository.save(item));
    }

    /**
     * Add several catalog items at once (multi-select picker) — one transaction,
     * same copy semantics as {@link #addItemFromCatalog} (price/unit/type/category
     * from each catalog item). A signed estimate is rejected (409) just like the
     * single add. Returns the full updated estimate so the client refreshes once.
     */
    @Transactional
    public EstimateResponse addItemsFromCatalogBatch(UUID estimateId,
                                                     List<AddCatalogItemsBatchRequest.Entry> entries,
                                                     UUID ownerId) {
        Estimate estimate = loadOwned(estimateId, ownerId);
        requireNotSigned(estimate);
        List<EstimateItem> toSave = new ArrayList<>();
        for (AddCatalogItemsBatchRequest.Entry e : entries) {
            CatalogItem source = catalogService.loadOwned(e.catalogItemId(), ownerId);
            toSave.add(EstimateItem.builder()
                    .estimate(estimate)
                    .type(source.getType())
                    .name(source.getName())
                    .category(source.getCategory())
                    .unit(source.getUnit())
                    .quantity(e.quantity())
                    .unitPrice(source.getDefaultPrice())
                    .sortOrder(e.sortOrder() == null ? 0 : e.sortOrder())
                    .build());
        }
        itemRepository.saveAll(toSave);
        return toResponse(estimate, itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId));
    }

    /**
     * Appends several already-resolved items to an estimate in one transaction — used by
     * the receipt import (lines parsed from a receipt photo, reviewed by the master). New
     * items go after the current last one (sortOrder continues). A signed estimate is
     * rejected (409) like every other item write. No catalog side-effect. Returns the
     * full updated estimate so the client refreshes once with recomputed sums.
     */
    @Transactional
    public EstimateResponse appendItems(UUID estimateId, List<ImportEstimateData.ImportItem> items, UUID ownerId) {
        Estimate estimate = loadOwned(estimateId, ownerId);
        requireNotSigned(estimate);
        List<EstimateItem> existing = itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId);
        int sortOrder = existing.stream().mapToInt(EstimateItem::getSortOrder).max().orElse(-1) + 1;
        List<EstimateItem> toSave = new ArrayList<>();
        for (ImportEstimateData.ImportItem in : items) {
            toSave.add(EstimateItem.builder()
                    .estimate(estimate)
                    .type(in.type())
                    .name(in.name().trim())
                    .category(CatalogService.normalizeCategory(in.category()))
                    .unit(in.unit())
                    .quantity(in.quantity().setScale(QUANTITY_SCALE, MONEY_ROUNDING))
                    .unitPrice(in.unitPrice().setScale(MONEY_SCALE, MONEY_ROUNDING))
                    .sortOrder(sortOrder++)
                    .build());
        }
        itemRepository.saveAll(toSave);
        return toResponse(estimate, itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId));
    }

    @Transactional
    public EstimateItemResponse updateItem(UUID estimateId,
                                           UUID itemId,
                                           EstimateItemRequest req,
                                           UUID ownerId) {
        Estimate estimate = loadOwned(estimateId, ownerId);
        requireNotSigned(estimate);
        EstimateItem item = loadItemInEstimate(estimateId, itemId);
        item.setType(req.type());
        item.setName(req.name().trim());
        item.setCategory(CatalogService.normalizeCategory(req.category()));
        item.setUnit(req.unit());
        Resolved r = resolveQuantity(estimate, req);
        item.setQuantity(r.quantity());
        item.setUnitPrice(req.unitPrice());
        item.setMeasurementRefs(r.refs());
        item.setQuantityManual(r.manual());
        if (req.sortOrder() != null) {
            item.setSortOrder(req.sortOrder());
        }
        return EstimateItemResponse.from(item);
    }

    @Transactional
    public void deleteItem(UUID estimateId, UUID itemId, UUID ownerId) {
        requireNotSigned(loadOwned(estimateId, ownerId));
        // Idempotent: a replayed offline delete of an already-gone item is a no-op, not a 404.
        itemRepository.findById(itemId)
                .filter(i -> i.getEstimate().getId().equals(estimateId))
                .ifPresent(itemRepository::delete);
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * A signed estimate is immutable: the signature certifies an exact set of
     * items and totals, so any edit would silently invalidate what the client
     * agreed to. Deleting the whole estimate stays allowed — that removes the
     * record instead of corrupting it. To revise the deal, create a new estimate.
     */
    private static Estimate requireNotSigned(Estimate estimate) {
        if (estimate.getStatus() == EstimateStatus.SIGNED) {
            throw new EstimateSignedException();
        }
        return estimate;
    }

    Estimate loadOwned(UUID estimateId, UUID ownerId) {
        Estimate estimate = estimateRepository.findById(estimateId)
                .orElseThrow(() -> new ResourceNotFoundException("Estimate not found: " + estimateId));
        if (!estimate.getProject().getOwner().getId().equals(ownerId)) {
            throw new AccessDeniedException("Estimate does not belong to the current user");
        }
        return estimate;
    }

    private EstimateItem loadItemInEstimate(UUID estimateId, UUID itemId) {
        EstimateItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Estimate item not found: " + itemId));
        if (!item.getEstimate().getId().equals(estimateId)) {
            throw new ResourceNotFoundException("Estimate item not found in estimate " + estimateId);
        }
        return item;
    }

    EstimateResponse toResponse(Estimate estimate, List<EstimateItem> items) {
        List<EstimateItemResponse> itemDtos = items.stream()
                .map(EstimateItemResponse::from)
                .toList();
        // Round per line first, then sum — keeps the user-visible math
        // consistent: line totals add up to subtotals, subtotals add up to total.
        BigDecimal worksSubtotal = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
        BigDecimal materialsSubtotal = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
        for (EstimateItem item : items) {
            BigDecimal lineTotal = item.getQuantity().multiply(item.getUnitPrice())
                    .setScale(MONEY_SCALE, MONEY_ROUNDING);
            if (item.getType() == ItemType.WORK) {
                worksSubtotal = worksSubtotal.add(lineTotal);
            } else {
                materialsSubtotal = materialsSubtotal.add(lineTotal);
            }
        }
        BigDecimal total = worksSubtotal.add(materialsSubtotal);
        BigDecimal deposit = estimate.getDepositAmount();
        BigDecimal balance = deposit == null
                ? total
                : total.subtract(deposit).max(BigDecimal.ZERO).setScale(MONEY_SCALE, MONEY_ROUNDING);
        return new EstimateResponse(
                estimate.getId(),
                estimate.getProject().getId(),
                estimate.getName(),
                estimate.getStatus(),
                estimate.getValidUntil(),
                estimate.getNotes(),
                estimate.getCreatedAt(),
                estimate.getUpdatedAt(),
                itemDtos,
                worksSubtotal,
                materialsSubtotal,
                total,
                deposit,
                balance
        );
    }

    private static String normalize(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * Server-side input for {@link #createFromImport}: the estimate name + deposit and
     * the final (master-confirmed) items. Units/types/amounts are already resolved by
     * {@code EstimateImportService} from the LLM extraction and the review-screen edits.
     */
    public record ImportEstimateData(String name, BigDecimal depositAmount, List<ImportItem> items) {
        public record ImportItem(
                ItemType type,
                String name,
                String category,
                Unit unit,
                BigDecimal quantity,
                BigDecimal unitPrice
        ) {}
    }
}
