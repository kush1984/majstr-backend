package com.majstr.backend.service;

import com.majstr.backend.dto.AddCatalogItemsBatchRequest;
import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.EstimateDuplicateRequest;
import com.majstr.backend.dto.EstimateItemFromCatalogRequest;
import com.majstr.backend.dto.EstimateItemRequest;
import com.majstr.backend.dto.EstimateItemResponse;
import com.majstr.backend.dto.EstimateItemsOrderRequest;
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
import com.majstr.backend.entity.PercentBaseKind;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectPhoto;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.service.measurement.MeasurementService;
import com.majstr.backend.exception.EmailNotVerifiedException;
import com.majstr.backend.exception.EstimateSignedException;
import com.majstr.backend.exception.InvalidEstimateStatusException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.LimitService;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectPhotoRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
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
    private final ProjectPhotoRepository photoRepository;
    private final StorageService storage;

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
        return recalculatedResponse(saved);
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
    /**
     * Copy an estimate, marking the chosen lines up — the foreman's two-price workflow.
     *
     * <p>A бригадир quotes his crew one price and the client another, and keeps the difference.
     * Until now that meant typing the estimate twice and an object economy with no way to tell
     * which figure was his: counting both said he earned his crew's wages as well as his margin.</p>
     *
     * <p><b>Every copied line records what it cost in the source</b> ({@code sourceUnitPrice}),
     * whether it was marked up or not. That single field is what the economy subtracts, and it is
     * deliberately not "the markup percent on the estimate": the percent stops being true the
     * moment the master marks up only some lines, edits one price afterwards, adds a line the crew
     * is not paid for, or deletes the source. The subtraction survives all four.</p>
     *
     * <p>The SOURCE stops counting in the economy here. It is what the foreman pays out, so leaving
     * it counted would report his crew's wages as his income — and this is the one automatic edit
     * to another estimate in the whole service, so it is stated rather than buried: the master can
     * switch it back on the estimate list if his object really works the other way.</p>
     *
     * <p>Not copied: the deposit (money already received against the source, not against this copy)
     * and portal visibility (nothing is ever shared by default). Measurement links ARE copied —
     * unlike a consolidation, this is the same object's same lines, so they still resolve.</p>
     */
    @Transactional
    public EstimateResponse duplicate(UUID estimateId, EstimateDuplicateRequest req, UUID ownerId) {
        Estimate source = loadOwned(estimateId, ownerId);
        UUID projectId = source.getProject().getId();
        limitService.requireCanAddEstimate(ownerId, projectId);

        List<EstimateItem> sourceItems = itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId);
        Set<UUID> toMarkUp = req.itemIds() == null
                ? sourceItems.stream().filter(i -> i.getType() == ItemType.WORK)
                        .map(EstimateItem::getId).collect(Collectors.toSet())
                : new HashSet<>(req.itemIds());

        // The request carries an UNSIGNED magnitude + a direction; from here everything runs off the
        // SIGNED percent, so a discount is markup with a minus and no branch of the copy logic below
        // has to know which it is. Stored signed too (the column is a label — nothing computes from
        // it), so the economy hint and the «… −15%» name read the direction straight off it.
        BigDecimal signedPercent = req.discount() ? req.markupPercent().negate() : req.markupPercent();

        Estimate copy = estimateRepository.save(Estimate.builder()
                .project(source.getProject())
                .name(duplicateName(req.name(), source.getName(), signedPercent))
                .validUntil(source.getValidUntil())
                .notes(source.getNotes())
                .duplicatedFromId(source.getId())
                .markupPercent(signedPercent)
                .build());
        projectRepository.incrementEstimatesCreated(projectId); // lifetime churn counter

        // discount → factor < 1 (1 − p/100); markup → factor > 1 (1 + p/100). Same multiply either way.
        BigDecimal factor = BigDecimal.ONE.add(signedPercent.movePointLeft(2));
        Map<UUID, EstimateItem> sourceById = sourceItems.stream()
                .collect(Collectors.toMap(EstimateItem::getId, i -> i));
        List<EstimateItem> copies = new ArrayList<>(sourceItems.size());
        for (EstimateItem item : sourceItems) {
            boolean marked = toMarkUp.contains(item.getId());
            // A PERCENT line is marked up on the PERCENT, not on the price — and only when its base
            // is not marked up itself. See markedUpPercent for the whole argument; the short of it
            // is that the markup must land exactly once.
            boolean percent = item.getUnit() == Unit.PERCENT;
            copies.add(EstimateItem.builder()
                    .estimate(copy)
                    .type(item.getType())
                    .name(item.getName())
                    .category(item.getCategory())
                    .unit(item.getUnit())
                    .quantity(percent && marked
                            ? markedUpPercent(item, sourceById, toMarkUp, factor)
                            : item.getQuantity())
                    .unitPrice(marked && !percent ? markedUp(item.getUnitPrice(), factor) : item.getUnitPrice())
                    // Recorded on EVERY line, not just the marked-up ones: a line passed through at
                    // cost earns nothing today, and if the master later raises its price by hand
                    // that difference is real margin the economy should see.
                    // On a PERCENT line this records the ORIGINAL PERCENT rather than a price:
                    // that is what the crew's sheet charged, and it is the figure the economy has
                    // to measure the client's sheet against.
                    .sourceUnitPrice(percent ? item.getQuantity() : item.getUnitPrice())
                    .percentBaseKind(item.getPercentBaseKind())
                    .percentBaseItemId(item.getPercentBaseItemId())
                    .baseDetached(item.isBaseDetached())
                    .sourceItemId(item.getId())
                    .sortOrder(item.getSortOrder())
                    .measurementRefs(item.getMeasurementRefs())
                    .quantityManual(item.isQuantityManual())
                    .build());
        }
        itemRepository.saveAll(copies);

        // ⚠️ A percentage line copied verbatim would still point at the PARENT's line. Left that
        // way it measures against an estimate it is not part of — EstimateMath would find no base
        // in its own list, treat the line as detached, and freeze it at nothing. Re-point every
        // base at the copy that came from the same source line.
        //
        // AFTER saveAll, and that ordering is load-bearing: a copy has no id until it is persisted,
        // so building this map first collected null values and Collectors.toMap rejects those. The
        // rows are managed here, so the re-pointing is flushed by dirty checking.
        Map<UUID, UUID> copyBySourceId = copies.stream()
                .filter(c -> c.getSourceItemId() != null && c.getId() != null)
                .collect(Collectors.toMap(EstimateItem::getSourceItemId, EstimateItem::getId));
        for (EstimateItem c : copies) {
            if (c.getPercentBaseItemId() != null) {
                UUID inCopy = copyBySourceId.get(c.getPercentBaseItemId());
                c.setPercentBaseItemId(inCopy);
                // The base did not make it into the copy at all — say so rather than silently
                // measuring against nothing.
                c.setBaseDetached(c.isBaseDetached() || inCopy == null);
            }
        }
        source.setCountInEconomy(false);

        return recalculatedResponse(copy);
    }

    /** Whole hryvnia. The client sees round numbers, and the economy reads the prices as stored,
     *  so nothing is lost to the rounding — it becomes part of the margin either way. */
    private static BigDecimal markedUp(BigDecimal price, BigDecimal factor) {
        return price.multiply(factor).setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * «Санвузол» → «Санвузол +15%» (markup) or «Санвузол -15%» (discount), so the two are tellable
     * apart in a list of variants. Only a fallback: the PWA composes the name itself and passes it,
     * so {@code requested} is normally set — see the duplicate-onConfirm note in the editor.
     */
    private static String duplicateName(String requested, String sourceName, BigDecimal signedPercent) {
        String explicit = normalize(requested);
        if (explicit != null) {
            return explicit;
        }
        String sign = signedPercent.signum() < 0 ? " -" : " +";
        String suffix = sign + signedPercent.abs().stripTrailingZeros().toPlainString() + "%";
        String base = normalize(sourceName);
        return base == null ? "Кошторис" + suffix : base + suffix;
    }

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
        // Distinct sources, original order. Listing one estimate twice (a double tap in the
        // picker, a retried request) would otherwise copy its items twice and silently
        // inflate the rollup's total. Note this is NOT item-level dedup — merging equal
        // POSITIONS from different estimates stays deliberate plain concat.
        Set<UUID> sourceIds = new LinkedHashSet<>(estimateIds);
        for (UUID sourceId : sourceIds) {
            Estimate source = loadOwned(sourceId, ownerId);
            if (!source.getProject().getId().equals(projectId)) {
                throw new AccessDeniedException("Estimate does not belong to project " + projectId);
            }
            // Sources keep counting (the rollup above is the excluded one instead).
            for (EstimateItem item : itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(sourceId)) {
                copies.add(copyForConsolidation(consolidated, item, sortOrder++));
            }
        }
        itemRepository.saveAll(copies);
        // Remember the lineage: the sources keep their receipts, and this is how the rollup offers
        // them for its PDF (all validated above, so store the whole set).
        consolidated.setConsolidationSourceIds(sourceIds);
        return recalculatedResponse(consolidated);
    }

    /**
     * One source line copied into a consolidated rollup.
     *
     * <p>An ordinary line is a plain by-value copy — {@code quantity × unitPrice} recomputes to the
     * same amount. A <b>percentage</b> line cannot stay live: «% від позиції» points at a base line
     * that is not in the rollup, and «% від кошторису» would re-measure the MERGED subtotal (a bigger
     * number than the source's). So it is <b>frozen</b> at the exact amount it contributed in its
     * source — {@code baseDetached} keeps that {@code lineTotal} through every recalculation — and
     * presented as a percent of that source sum (MANUAL, {@code unitPrice} reconstructed from the
     * amount) so it reads «−10 % від 5 000 ₴» rather than «база видалена». The frozen amount is exact;
     * only the reconstructed base is display-rounded.</p>
     */
    private EstimateItem copyForConsolidation(Estimate consolidated, EstimateItem item, int sortOrder) {
        EstimateItem.EstimateItemBuilder copy = EstimateItem.builder()
                .estimate(consolidated)
                .type(item.getType())
                .name(item.getName())
                .category(item.getCategory())
                .unit(item.getUnit())
                .quantity(item.getQuantity())
                .unitPrice(item.getUnitPrice())
                .sortOrder(sortOrder);
        if (item.getUnit() == Unit.PERCENT) {
            BigDecimal amount = item.getLineTotal() == null
                    ? BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING)
                    : item.getLineTotal();
            copy.lineTotal(amount)
                    .baseDetached(true)
                    .percentBaseKind(PercentBaseKind.MANUAL)
                    .unitPrice(reconstructPercentBase(amount, item.getQuantity()));
        }
        return copy.build();
    }

    /** The sum a frozen percentage was OF, from its amount and rate: {@code amount × 100 / percent}
     *  (display only — the money is the stored {@code lineTotal}). Zero rate → zero, no divide. */
    private static BigDecimal reconstructPercentBase(BigDecimal amount, BigDecimal percent) {
        if (percent.signum() == 0) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
        }
        return amount.multiply(new BigDecimal("100")).divide(percent, MONEY_SCALE, MONEY_ROUNDING);
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
    /**
     * Deletes an estimate. <b>Idempotent</b> — one that is already gone is a no-op, not a 404,
     * so a replayed offline delete isn't reported back to the master as a failure. A SIGNED
     * estimate is still refused (409), and ownership is still enforced, whenever the row exists.
     */
    public void delete(UUID estimateId, UUID ownerId) {
        Estimate estimate = estimateRepository.findById(estimateId).orElse(null);
        if (estimate == null) {
            return;
        }
        if (!estimate.getProject().getOwner().getId().equals(ownerId)) {
            throw new AccessDeniedException("Estimate does not belong to the current user");
        }
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
        return recalculatedResponse(estimate);
    }

    @Transactional(readOnly = true)
    public byte[] renderPdf(UUID estimateId, UUID ownerId) throws IOException, DocumentException {
        return renderPdf(estimateId, ownerId, List.of());
    }

    /**
     * Owner download, optionally with a chosen set of receipt photos appended as a «ЧЕКИ» section.
     * Only receipts genuinely linked to THIS estimate are embedded — any other id is silently
     * dropped, so a crafted request can never pull a foreign estimate's (or another owner's) photo
     * into the PDF.
     */
    @Transactional(readOnly = true)
    public byte[] renderPdf(UUID estimateId, UUID ownerId, List<UUID> receiptPhotoIds)
            throws IOException, DocumentException {
        Estimate estimate = loadOwned(estimateId, ownerId);
        // The PDF is a client-facing deliverable — gate it behind a verified email
        // (even on FREE) so a throwaway account can't churn out finished estimates.
        if (!estimate.getProject().getOwner().isEmailVerified()) {
            throw new EmailNotVerifiedException("error.email-not-verified");
        }
        return renderPdf(estimate, loadPdfImages(estimate, receiptPhotoIds));
    }

    /**
     * Used by both authenticated and public flows. Caller has already
     * validated access (ownership or share-link token). Never includes receipts — those are the
     * master's private records and only ride the owner download.
     */
    @Transactional(readOnly = true)
    public byte[] renderPdf(Estimate estimate) throws IOException, DocumentException {
        return renderPdf(estimate, List.of());
    }

    private byte[] renderPdf(Estimate estimate, List<byte[]> receiptImages)
            throws IOException, DocumentException {
        Project project = estimate.getProject();
        List<EstimateItem> items = itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimate.getId());
        return pdfService.render(new EstimatePdfService.PdfModel(
                project.getOwner(),
                project,
                project.getClient(),
                estimate,
                items,
                receiptImages
        ));
    }

    /**
     * Bytes of the requested photos, in the caller's order, dropping any id that is not a photo of
     * THIS estimate's project (the ownership guarantee — a crafted id can't pull another owner's
     * photo) or whose file is missing. Accepts both receipts and plain progress photos: a master
     * sometimes saves a receipt as an ordinary photo, and may attach it to the PDF from there.
     */
    private List<byte[]> loadPdfImages(Estimate estimate, List<UUID> photoIds) throws IOException {
        if (photoIds == null || photoIds.isEmpty()) {
            return List.of();
        }
        UUID projectId = estimate.getProject().getId();
        List<byte[]> images = new ArrayList<>();
        for (UUID id : photoIds) {
            ProjectPhoto photo = photoRepository.findByIdAndProjectId(id, projectId).orElse(null);
            if (photo == null) {
                continue; // not a photo of this estimate's project — never embed a foreign photo
            }
            try (InputStream in = storage.open(photo.getStorageKey()).orElse(null)) {
                if (in != null) {
                    images.add(in.readAllBytes());
                }
            }
        }
        return images;
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
                .percentBaseKind(percentKindOf(req))
                .percentBaseItemId(validBaseItemId(estimateId, req))
                .measurementRefs(r.refs())
                .quantityManual(r.manual())
                .sortOrder(req.sortOrder() == null ? 0 : req.sortOrder())
                .build();
        return savedItemResponse(estimate, item);
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
        return addItemFromCatalog(estimateId, catalogItemId, req, ownerId, null);
    }

    /**
     * Add a line copied from a catalog position, optionally with a CLIENT-PROVIDED id so the
     * add is idempotent on replay — the offline path.
     *
     * <p>Picking from the catalog is how estimates are actually built, so it has to work on
     * site. It cannot simply be routed through {@link #addItem} offline: that endpoint's
     * validated form requires {@code unitPrice >= 0.01}, while a catalog position may legally
     * cost 0 (V27/V29 relaxed both CHECKs precisely for this), so every 0-price position would
     * queue happily and then be rejected on replay.
     */
    @Transactional
    public EstimateItemResponse addItemFromCatalog(UUID estimateId,
                                                   UUID catalogItemId,
                                                   EstimateItemFromCatalogRequest req,
                                                   UUID ownerId,
                                                   UUID requestedId) {
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
        CatalogItem source = catalogService.loadOwned(catalogItemId, ownerId);
        // A PERCENT position carries its PERCENT in the catalog's price column, not a price. Our
        // own seed data says so out loud — «Укладання плитки по діагоналі (плюс % до м.кв.)» with
        // default_price 33 means 33 %, and V82 shipped nine of these into the live tiling catalog.
        // Copying that into unitPrice would produce «база 33 ₴», which is nonsense.
        //
        // The base is left EMPTY on purpose: only the master knows which line this надбавка is a
        // percentage of. POSITION is the right default because the wording («плюс % до м.кв.»)
        // means "of the m² work this is an extra on".
        boolean percent = source.getUnit() == Unit.PERCENT;
        // Copy the category from the catalog item so the estimate can group too.
        EstimateItem item = EstimateItem.builder()
                .id(requestedId)
                .estimate(estimate)
                .type(source.getType())
                .name(source.getName())
                .category(source.getCategory())
                .unit(source.getUnit())
                .quantity(percent ? source.getDefaultPrice() : req.quantity())
                .unitPrice(percent ? BigDecimal.ZERO : source.getDefaultPrice())
                .percentBaseKind(percent ? PercentBaseKind.POSITION : null)
                .sortOrder(req.sortOrder() == null ? 0 : req.sortOrder())
                .build();
        return savedItemResponse(estimate, item);
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
        // Idempotency BEFORE the signed check, like every other replayable write: a replay of a
        // batch that already landed must return quietly, not 409 because the client signed the
        // estimate in the meantime.
        List<AddCatalogItemsBatchRequest.Entry> fresh = new ArrayList<>();
        for (AddCatalogItemsBatchRequest.Entry e : entries) {
            if (e.id() != null) {
                var existing = itemRepository.findById(e.id());
                if (existing.isPresent()) {
                    if (!existing.get().getEstimate().getId().equals(estimateId)) {
                        throw new AccessDeniedException("Item belongs to a different estimate");
                    }
                    continue; // already added by an earlier attempt of this same batch
                }
            }
            fresh.add(e);
        }
        if (fresh.isEmpty()) {
            return recalculatedResponse(estimate);
        }
        requireNotSigned(estimate);
        List<EstimateItem> toSave = new ArrayList<>();
        for (AddCatalogItemsBatchRequest.Entry e : fresh) {
            CatalogItem source = catalogService.loadOwned(e.catalogItemId(), ownerId);
            toSave.add(EstimateItem.builder()
                    .id(e.id())
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
        return recalculatedResponse(estimate);
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
        return recalculatedResponse(estimate);
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
        item.setPercentBaseKind(percentKindOf(req));
        item.setPercentBaseItemId(validBaseItemId(estimateId, req));
        // Re-attaching a base is how the master undoes a detach; choosing one again means he wants
        // the live link back, and leaving the flag set would silently keep the amount frozen.
        item.setBaseDetached(item.getPercentBaseKind() == PercentBaseKind.POSITION
                && item.getPercentBaseItemId() == null);
        item.setMeasurementRefs(r.refs());
        item.setQuantityManual(r.manual());
        if (req.sortOrder() != null) {
            item.setSortOrder(req.sortOrder());
        }
        // The edited row is included explicitly for the same reason the added one is: the query
        // need not see pending changes, and an update that answered with a stale amount would be
        // contradicted by the very next read.
        List<EstimateItem> items =
                new ArrayList<>(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId));
        if (items.stream().noneMatch(i -> item.getId() != null && item.getId().equals(i.getId()))) {
            items.add(item);
        }
        EstimateMath.recalculate(items);
        return EstimateItemResponse.from(item);
    }

    /**
     * Apply the arrangement the master dragged into place: {@code sortOrder} becomes the index in the
     * list and the category comes along with each line, so moving a line into another section is one
     * atomic change rather than a reorder plus an edit that could half-apply.
     *
     * <p>Sections are not rows — a section IS the lines sharing a category, ordered by the first of
     * them — so dragging a whole section needs nothing here beyond what dragging a line needs.</p>
     *
     * <p>Two things this is deliberately lenient about, because the caller may be an offline queue
     * replaying an arrangement built minutes or days ago:</p>
     * <ul>
     *   <li>an id that no longer exists is skipped, not a 404 — the line was deleted in the meantime
     *       and failing forever over it would wedge the queue (same reasoning as
     *       {@link #deleteItem});</li>
     *   <li>a line the request does NOT mention is kept and appended after the listed ones, in its
     *       existing relative order. It was almost certainly added on another device after this
     *       arrangement was captured; dropping it, or leaving it with a colliding sortOrder, would
     *       lose or scramble work the master can see on the other screen.</li>
     * </ul>
     */
    @Transactional
    public EstimateResponse reorderItems(UUID estimateId,
                                         EstimateItemsOrderRequest req,
                                         UUID ownerId) {
        Estimate estimate = loadOwned(estimateId, ownerId);
        requireNotSigned(estimate);

        List<EstimateItem> existing = itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId);
        Map<UUID, EstimateItem> byId = existing.stream()
                .collect(Collectors.toMap(EstimateItem::getId, i -> i));

        int position = 0;
        Set<UUID> placed = new LinkedHashSet<>();
        for (EstimateItemsOrderRequest.Line line : req.items()) {
            EstimateItem item = byId.get(line.id());
            if (item == null || !placed.add(line.id())) {
                continue;   // gone since the arrangement was captured, or listed twice
            }
            item.setSortOrder(position++);
            item.setCategory(CatalogService.normalizeCategory(line.category()));
        }
        // Whatever the request never mentioned keeps its relative order, after the rest.
        for (EstimateItem item : existing) {
            if (!placed.contains(item.getId())) {
                item.setSortOrder(position++);
            }
        }
        return recalculatedResponse(estimate);
    }

    @Transactional
    public void deleteItem(UUID estimateId, UUID itemId, UUID ownerId) {
        deleteItems(estimateId, List.of(itemId), ownerId);
    }

    /**
     * Remove several lines at once — one request, one transaction.
     *
     * <p><b>Why not just call the single delete N times.</b> A master who applies «УСІ ПЛИТОЧНІ
     * РОБОТИ» gets 167 positions and keeps perhaps thirty. One-at-a-time that is 130 taps and, on a
     * phone, 130 queued operations each with its own chance of failing on a bad connection —
     * leaving an estimate half-trimmed with no way to tell which half. One operation either
     * happened or did not.</p>
     *
     * <p>Idempotent by construction: ids already gone are simply not found. A replayed offline
     * delete is a no-op, never a 404.</p>
     */
    @Transactional
    public void deleteItems(UUID estimateId, List<UUID> itemIds, UUID ownerId) {
        requireNotSigned(loadOwned(estimateId, ownerId));
        if (itemIds == null || itemIds.isEmpty()) {
            return;
        }
        List<EstimateItem> doomed = itemRepository.findAllById(itemIds).stream()
                .filter(i -> i.getEstimate().getId().equals(estimateId))
                .toList();
        if (doomed.isEmpty()) {
            return;
        }
        cascadeIntoDuplicates(estimateId, doomed.stream().map(EstimateItem::getId).toList());
        detachPercentagesPointingAt(estimateId, doomed.stream().map(EstimateItem::getId).toList());
        itemRepository.deleteAll(doomed);
        EstimateMath.recalculate(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId));
    }

    /**
     * A percentage line whose base is being deleted keeps its money and loses its link.
     *
     * <p>The FK is {@code ON DELETE SET NULL}, so the database would quietly leave a percentage
     * pointing at nothing — and the next recalculation would have no base to measure against.
     * Marking it detached here is what turns that into a statement: the last computed amount stays
     * (the master is charging for this work; zeroing it would be data loss dressed up as tidiness),
     * the recalculation leaves it alone, and the row can say «база видалена» so he picks a new one
     * or types a sum.</p>
     */
    private void detachPercentagesPointingAt(UUID estimateId, List<UUID> deletedItemIds) {
        Set<UUID> gone = new HashSet<>(deletedItemIds);
        for (EstimateItem item : itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId)) {
            if (item.getPercentBaseItemId() != null && gone.contains(item.getPercentBaseItemId())) {
                item.setBaseDetached(true);
            }
        }
    }

    /**
     * A line removed from the master-price estimate is removed from its client copies too.
     *
     * <p>One direction only. Trimming happens in the parent — that is where a big template was
     * applied — and a copy that kept the removed positions would silently undo the work and put
     * them back in front of the client. The reverse is deliberately NOT true: the duplicate is the
     * client's sheet, and what the master takes out of it there is a decision about that sheet
     * alone.</p>
     *
     * <p><b>A SIGNED copy is skipped.</b> Its signature certifies an exact set of lines and totals,
     * and that outranks convenience — this is why the cascade lives here and not in a database
     * {@code ON DELETE CASCADE}, which could not know about it. The parent's line still goes; the
     * signed copy keeps its own, its {@code sourceItemId} falls to NULL, and the money is
     * unaffected because the economy reads the price stored on the copy's own line.</p>
     */
    private void cascadeIntoDuplicates(UUID parentEstimateId, List<UUID> deletedItemIds) {
        for (Estimate copy : estimateRepository.findByDuplicatedFromId(parentEstimateId)) {
            if (copy.getStatus() == EstimateStatus.SIGNED) {
                log.info("Estimate {} is signed — its lines are left alone by the parent's deletion",
                        copy.getId());
                continue;
            }
            List<EstimateItem> twins = itemRepository.findByEstimateIdAndSourceItemIdIn(
                    copy.getId(), deletedItemIds);
            if (!twins.isEmpty()) {
                itemRepository.deleteAll(twins);
            }
        }
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

    /**
     * Recompute every line, persist the amounts, and answer with the result — the WRITE path.
     *
     * <p>Every mutating method ends here, and nothing else recalculates. That split is the guarantee
     * the percentage feature needed: a read never rewrites anything, so a SIGNED estimate — which no
     * mutating method will touch, {@code requireNotSigned} sees to that — cannot drift behind the
     * client's back. The entities are managed, so the new {@code lineTotal}s are flushed by dirty
     * checking; the client never sends that field and it is never read from a request.</p>
     */
    /**
     * Save one line, recompute the estimate, and answer with that line.
     *
     * <p>The recalculation is not optional even for a single row: adding a line moves the subtotal,
     * and every «% від усього кошторису» line is measured against it. Returning the row without it
     * would hand the client a number the next read contradicts.</p>
     */
    /** Only a PERCENT line has a base; anything else records none, whatever the client sent. */
    private static PercentBaseKind percentKindOf(EstimateItemRequest req) {
        if (req.unit() != Unit.PERCENT) {
            return null;
        }
        return req.percentBaseKind() == null ? PercentBaseKind.MANUAL : req.percentBaseKind();
    }

    /**
     * The named base, but only if it is an ORDINARY line of THIS estimate.
     *
     * <p>This is the entire cycle protection the feature has, and it is enough: a percentage may
     * never point at a percentage, so no chain of them can close on itself. There is no graph to
     * walk and no cycle detection to get wrong. A base that fails the check is dropped to null
     * rather than rejected — the client filters the picker the same way, so a value arriving here
     * means a stale screen, not an argument worth a 400.</p>
     */
    private UUID validBaseItemId(UUID estimateId, EstimateItemRequest req) {
        if (percentKindOf(req) != PercentBaseKind.POSITION || req.percentBaseItemId() == null) {
            return null;
        }
        return itemRepository.findById(req.percentBaseItemId())
                .filter(base -> base.getEstimate().getId().equals(estimateId))
                .filter(base -> base.getUnit() != Unit.PERCENT)
                .map(EstimateItem::getId)
                .orElse(null);
    }

    /**
     * The percent a duplicated «%» line should carry, so the markup lands EXACTLY ONCE.
     *
     * <p>Multiplying the amount is not an option — a percentage has no price to raise — and leaving
     * it alone is a hole in the whole two-price mechanic. Worked example: шафа 5 000 (material),
     * монтаж «20 % від шафи» = 1 000 (work), markup +30 %. Materials are not marked up, so the base
     * stays 5 000; if the percent also stays 20 %, the copy still charges 1 000 and the work the
     * foreman meant to sell dearer sells at cost. The more percentage work an estimate holds, the
     * less of the duplicate mechanic survives.</p>
     *
     * <p>So the rule is about where the markup already landed:</p>
     * <ul>
     *   <li><b>base is a MATERIAL</b> (not marked up) → raise the percent: 20 % × 1,3 = 26 %,
     *       giving 1 300 against the parent's 1 000;</li>
     *   <li><b>base is a WORK that is being marked up</b> → leave the percent; it already applies
     *       to a base that grew, and raising both would mark the line up twice;</li>
     *   <li><b>MANUAL base</b> → a sum typed by hand is not marked up, so treat it like a material
     *       and raise the percent;</li>
     *   <li><b>TOTAL base</b> → a «% від кошторису» line is always left alone. A WORK one rides the
     *       works subtotal the markup already grew; a MATERIAL one measures the materials subtotal,
     *       which passes through at cost — so the percent passes through at cost too, like the
     *       materials it is a share of. Margin on such a line is a manual edit in the copy.</li>
     * </ul>
     */
    private static BigDecimal markedUpPercent(EstimateItem item,
                                              Map<UUID, EstimateItem> sourceById,
                                              Set<UUID> toMarkUp,
                                              BigDecimal factor) {
        PercentBaseKind kind = item.getPercentBaseKind() == null
                ? PercentBaseKind.MANUAL : item.getPercentBaseKind();
        boolean baseAlreadyMarkedUp = switch (kind) {
            // A «% від кошторису» is left alone: a WORK one rides the works subtotal the markup grew,
            // a MATERIAL one passes through at cost like the materials it measures (margin there is a
            // manual edit in the copy).
            case TOTAL -> true;
            case MANUAL -> false;
            case POSITION -> {
                EstimateItem base = item.getPercentBaseItemId() == null
                        ? null : sourceById.get(item.getPercentBaseItemId());
                yield base != null && toMarkUp.contains(base.getId());
            }
        };
        return baseAlreadyMarkedUp
                ? item.getQuantity()
                : item.getQuantity().multiply(factor).setScale(QUANTITY_SCALE, MONEY_ROUNDING);
    }

    private EstimateItemResponse savedItemResponse(Estimate estimate, EstimateItem item) {
        // Recalculate BEFORE saving. The other order looked equivalent and was not: the INSERT then
        // carried line_total = NULL, which the NOT NULL column rejects outright — a percentage line
        // could not be added at all. It also has to be this way round for the answer to be right,
        // since adding a line moves the subtotal every «% від усього кошторису» is measured against.
        //
        // The new row is added to the list explicitly rather than trusted to come back from the
        // query: save() need not flush, so the SELECT can legitimately not see it yet.
        List<EstimateItem> items =
                new ArrayList<>(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimate.getId()));
        if (items.stream().noneMatch(i -> item.getId() != null && item.getId().equals(i.getId()))) {
            items.add(item);
        }
        EstimateMath.recalculate(items);
        return EstimateItemResponse.from(itemRepository.save(item));
    }

    EstimateResponse recalculatedResponse(Estimate estimate) {
        List<EstimateItem> items =
                itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimate.getId());
        EstimateMath.recalculate(items);
        return toResponse(estimate, items);
    }

    /**
     * The READ path: sums what is stored, and computes nothing.
     *
     * <p>Line amounts are written by {@link #recalculatedResponse}. A percentage of the subtotal
     * cannot be derived per row anyway — the row depends on the total and the total on the row —
     * which is exactly why {@code line_total} is a column (V88).</p>
     */
    EstimateResponse toResponse(Estimate estimate, List<EstimateItem> items) {
        List<EstimateItemResponse> itemDtos = items.stream()
                .map(EstimateItemResponse::from)
                .toList();
        // Amounts are already rounded per line, so subtotals add up to the total exactly — the
        // arithmetic a master can check by hand.
        BigDecimal worksSubtotal = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
        BigDecimal materialsSubtotal = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
        for (EstimateItem item : items) {
            BigDecimal lineTotal = item.getLineTotal() == null
                    ? BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING)
                    : item.getLineTotal();
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
                balance,
                List.copyOf(estimate.getConsolidationSourceIds())
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
