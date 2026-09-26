package com.majstr.backend.service;

import com.majstr.backend.dto.ActProgressResponse;
import com.majstr.backend.dto.WorkActCreateRequest;
import com.majstr.backend.dto.WorkActItemsRequest;
import com.majstr.backend.dto.WorkActResponse;
import com.majstr.backend.dto.WorkActSignOfflineRequest;
import com.majstr.backend.dto.WorkActUpdateRequest;
import com.lowagie.text.DocumentException;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateKind;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActItem;
import com.majstr.backend.entity.WorkActLineKind;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.exception.WorkActConflictException;
import com.majstr.backend.exception.WorkActSignedException;
import com.majstr.backend.exception.WorkActValidationException;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.WorkActItemRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import com.majstr.backend.repository.WorkActRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Work acts (Акти виконаних робіт) — a document built from a signed estimate's positions, signed
 * separately by the client (acts iteration).
 *
 * <p>Everything an act holds is a FROZEN copy: the estimate line can be edited or deleted, but an
 * act sent to the client must read identically a year later. Progress is never denormalized —
 * "виконано з початку" is always Σ quantity over SIGNED acts. Key invariants, all enforced here:
 * one open (DRAFT/SENT) act per object; a signed act is immutable; a FINAL act closes the object;
 * numbers are continuous per master; and signing an act with additional (off-estimate) positions
 * creates a SIGNED ADDENDUM estimate in the same transaction so «Прийнято актами» can never exceed
 * «За договором».</p>
 */
@Service
@RequiredArgsConstructor
public class WorkActService {

    private static final int MONEY_SCALE = 2;
    private static final int QUANTITY_SCALE = 3;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final int MAX_NUMBER_RETRIES = 5;

    private final WorkActRepository workActRepository;
    private final WorkActItemRepository itemRepository;
    private final WorkActReceiptRepository receiptRepository;
    private final WorkActCreator creator;
    private final WorkActResponseFactory responseFactory;
    private final WorkActPdfService pdfService;
    private final ActCumulativeCalculator cumulativeCalculator;
    private final ActAddendumCreator addendumCreator;
    private final ActSignedCopyService signedCopy;
    private final ActReceiptCompleteness receiptCompleteness;
    private final ActLineBinder lineBinder;
    private final ActAdjustmentCalculator adjustmentCalculator;
    private final ActReceiptReconciler receiptReconciler;
    private final ReceiptIdentityIndex identityIndex;
    private final ProjectService projectService;
    private final EstimateRepository estimateRepository;
    private final EstimateItemRepository estimateItemRepository;

    // ---- reads (owner-scoped, ungated) ------------------------------------

    @Transactional(readOnly = true)
    public List<WorkActResponse> list(UUID projectId, UUID ownerId) {
        projectService.loadOwned(projectId, ownerId);
        // The «same paper» index (B-04) is loaded ONCE for the object and handed to every act: it
        // answers in two queries whatever the list length, and per-act it would spend two a row to
        // say the same thing.
        ReceiptIdentityIndex.Twins twins = identityIndex.forProject(projectId, List.of());
        return workActRepository.findByProjectIdOrderByIssuedAtDescCreatedAtDesc(projectId).stream()
                .map(a -> responseFactory.build(a, twins))
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkActResponse get(UUID id, UUID ownerId) {
        return responseFactory.build(loadOwned(id, ownerId));
    }

    /** Owner download of the act PDF. Estimate group names are resolved from the referenced
     *  estimates (a null/blank name falls back to «Кошторис»). */
    @Transactional(readOnly = true)
    public byte[] renderPdf(UUID id, UUID ownerId) throws IOException, DocumentException {
        WorkAct act = loadOwned(id, ownerId);
        List<WorkActItem> items = itemRepository.findByWorkActIdOrderBySortOrderAscIdAsc(id);
        Map<UUID, String> names = new HashMap<>();
        items.stream().map(WorkActItem::getEstimateId).filter(Objects::nonNull).distinct().forEach(estId ->
                estimateRepository.findById(estId).ifPresent(e ->
                        names.put(estId, e.getName() == null || e.getName().isBlank() ? "Кошторис" : e.getName().trim())));
        Project project = act.getProject();
        return pdfService.render(new WorkActPdfService.PdfModel(
                project.getOwner(), project, project.getClient(), act, items,
                receiptRows(id), names, act.getDocHash(),
                cumulativeCalculator.forDownload(act, items, receiptRepository.sumByWorkActId(id))));
    }

    /**
     * Every line of the object's SIGNED estimates, with how much is already done (across SIGNED
     * acts) and what remains. Percent adjustment lines and ADDENDUM rollups are excluded — neither
     * is "closeable" work. Ungated (the master can always see his own progress).
     */
    @Transactional(readOnly = true)
    public ActProgressResponse progress(UUID projectId, UUID ownerId) {
        projectService.loadOwned(projectId, ownerId);
        Map<UUID, BigDecimal> done = lineBinder.signedDone(projectId);
        List<ActProgressResponse.Line> lines = new ArrayList<>();
        for (Estimate e : estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId)) {
            // Only SIGNED, non-ADDENDUM, and IN the economy: a kosторис the master excluded from the
            // economy is not income, so there is nothing to «close» by an act against it — and letting
            // it into the picker is what let «Прийнято актами» outgrow «За договором» (acts-fix).
            if (e.getStatus() != EstimateStatus.SIGNED || e.getKind() == EstimateKind.ADDENDUM
                    || !e.isCountInEconomy()) {
                continue;
            }
            for (EstimateItem it : estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(e.getId())) {
                if (it.getUnit() == Unit.PERCENT) {
                    continue; // a «%» line has no quantity to close
                }
                BigDecimal doneQty = done.getOrDefault(it.getId(), quantityZero());
                BigDecimal remaining = it.getQuantity().subtract(doneQty).max(BigDecimal.ZERO)
                        .setScale(QUANTITY_SCALE, ROUNDING);
                lines.add(new ActProgressResponse.Line(
                        e.getId(), e.getName(), e.getCreatedAt(), it.getId(), it.getType(), it.getName(),
                        it.getCategory(), it.getUnit(), it.getUnitPrice(), it.getQuantity(), doneQty, remaining));
            }
        }
        return new ActProgressResponse(lines);
    }

    // ---- create (gated; numbering retried on race) ------------------------

    /**
     * Create a draft act. Not {@code @Transactional}: each numbering attempt is its own transaction
     * (in {@link WorkActCreator}) so a lost race on {@code UNIQUE(user_id, number)} rolls back and we
     * retry with a fresh number. Idempotency (X-Entity-Uuid) and every gate live inside the attempt.
     */
    public WorkActResponse create(UUID projectId, WorkActCreateRequest req, UUID ownerId, UUID requestedId) {
        int attempts = 0;
        while (true) {
            try {
                return creator.attempt(projectId, req, ownerId, requestedId);
            } catch (DataIntegrityViolationException e) {
                // Almost certainly the number UNIQUE clash — another act (another object, same
                // master) grabbed it concurrently. Recompute and retry a bounded number of times.
                if (++attempts >= MAX_NUMBER_RETRIES) {
                    throw e;
                }
            }
        }
    }

    // ---- writes (immutable once signed) -----------------------------------

    @Transactional
    public WorkActResponse updateHeader(UUID id, WorkActUpdateRequest req, UUID ownerId) {
        WorkAct act = requireNotSigned(loadOwnedForUpdate(id, ownerId));
        act.setKind(req.kind());
        act.setTitle(trim(req.title()));
        act.setIssuedAt(req.issuedAt());
        // A «7/2026»-shaped number follows the issue year while the act is still open: the year in
        // the string is display, the SEQUENCE is the identity — and continuous per-master numbering
        // makes the sequence unique across years, so the rewrite cannot collide (review fix).
        int slash = act.getNumber().indexOf('/');
        if (slash > 0) {
            act.setNumber(act.getNumber().substring(0, slash) + "/" + req.issuedAt().getYear());
        }
        act.setPeriodFrom(req.periodFrom());
        act.setPeriodTo(req.periodTo());
        act.setPlace(trim(req.place()));
        act.setContractRef(trim(req.contractRef()));
        act.setNote(trim(req.note()));
        if (req.showMaterials() != null) {
            act.setShowMaterials(req.showMaterials());
        }
        if (req.showCumulative() != null) {
            act.setShowCumulative(req.showCumulative());
        }
        if (req.receiptsToExpenses() != null) {
            act.setReceiptsToExpenses(req.receiptsToExpenses());
        }
        if (req.showReceiptPhotos() != null) {
            act.setShowReceiptPhotos(req.showReceiptPhotos());
        }
        act.setAdvanceOffset(req.advanceOffset());
        return responseFactory.build(act);
    }

    /**
     * Replace the act's lines wholesale. {@code line_total} is server-authored (unitPrice ×
     * quantity); {@code cumulative_before} is frozen from the object's SIGNED acts. That freeze is
     * stable in practice because the one-open-act rule means no OTHER act can be signed while this
     * one is open, so re-saving doesn't move the figure.
     */
    @Transactional
    public WorkActResponse replaceItems(UUID id, WorkActItemsRequest req, UUID ownerId) {
        WorkAct act = requireNotSigned(loadOwnedForUpdate(id, ownerId));
        touch(act); // the lines ARE the document (B-60/B-61)
        ActLineBinder.Bound bound = lineBinder.bind(req.items(), act.getProject().getId());
        itemRepository.deleteByWorkActId(id);
        itemRepository.flush(); // clear before re-inserting so nothing collides
        Map<UUID, BigDecimal> done = bound.done();
        List<WorkActItem> items = new ArrayList<>();
        int sort = 0;
        for (WorkActItemsRequest.Line line : req.items()) {
            // A linked line copies the ESTIMATE, not the request (B-56): the client signed those
            // words at that price, and an act is the document that says how much of them is done.
            // Only the quantity is the master's — which is all the act editor ever offers him.
            EstimateItem source = line.estimateItemId() == null
                    ? null : bound.linked().get(line.estimateItemId());
            BigDecimal quantity = line.quantity().setScale(QUANTITY_SCALE, ROUNDING);
            BigDecimal unitPrice = (source == null ? line.unitPrice() : source.getUnitPrice())
                    .setScale(MONEY_SCALE, ROUNDING);
            BigDecimal cumulativeBefore = source == null
                    ? quantityZero()
                    : done.getOrDefault(line.estimateItemId(), quantityZero());
            items.add(WorkActItem.builder()
                    .workAct(act)
                    .lineKind(source == null ? WorkActLineKind.ADDITIONAL : WorkActLineKind.ESTIMATE)
                    .estimateItemId(line.estimateItemId())
                    // Derived from the item, never trusted from the request (review fix): a null or
                    // mismatched estimateId would land the line in the «IS NULL» branch of
                    // sumSignedActLineTotals and mis-group the PDF.
                    .estimateId(source == null ? null : source.getEstimate().getId())
                    .type(source == null ? line.type() : source.getType())
                    .name(source == null ? line.name().trim() : source.getName().trim())
                    .category(CatalogService.normalizeCategory(
                            source == null ? line.category() : source.getCategory()))
                    .unit(source == null ? line.unit() : source.getUnit())
                    .unitPrice(unitPrice)
                    .quantity(quantity)
                    .lineTotal(unitPrice.multiply(quantity).setScale(MONEY_SCALE, ROUNDING))
                    .cumulativeBefore(cumulativeBefore)
                    .sortOrder(sort++)
                    .build());
        }
        // The estimate's own discounts and surcharges, prorated by what this act closes (B-55).
        // Written here rather than at sign time so the client sees the discount on the page he is
        // about to sign, not only afterwards.
        items.addAll(adjustmentCalculator.adjustmentsFor(act, items, sort));
        itemRepository.saveAll(items);
        return responseFactory.build(act);
    }

    @Transactional
    public void delete(UUID id, UUID ownerId) {
        WorkAct act = loadOwnedForUpdate(id, ownerId);
        if (act.getStatus() != WorkActStatus.DRAFT && act.getStatus() != WorkActStatus.REJECTED) {
            throw new WorkActConflictException("error.work-act.not-deletable", "WORK_ACT_NOT_DELETABLE");
        }
        workActRepository.delete(act); // items cascade at the DB level
    }

    /**
     * Sign an act on the client's behalf (the offline path — signer_ip/UA not recorded). Creates
     * the ADDENDUM estimate first (in this same transaction) if the act carries additional
     * positions, so «За договором» absorbs them before the act counts as «Прийнято». Leaves the
     * SAME artifacts as the portal sign (review fix): the doc_hash tamper stamp and, when the
     * client has an email, a PDF copy — an offline signature is exactly the case where an
     * independent trace matters most.
     */
    @Transactional
    public WorkActResponse signOffline(UUID id, WorkActSignOfflineRequest req, UUID ownerId)
            throws IOException, DocumentException {
        // FOR UPDATE, and before anything else (B-60): a receipt or a line landing between this
        // read and the commit would be billed by «Прийнято актами» while sitting outside the
        // document the client signed. Every writer takes the same lock, so one of the two waits.
        WorkAct act = requireNotSigned(loadOwnedForUpdate(id, ownerId));
        requireItems(id); // a signed act is immutable and undeletable — never let an empty one in
        receiptCompleteness.requireAllPriced(id); // …nor one whose receipts are not priced yet
        // …nor one whose estimate moved under it since the save (B-56): reopened, uncounted or
        // already closed by another act. Structure only — the prices stay the frozen copy.
        lineBinder.requireStillValid(act);
        addendumCreator.createIfNeeded(act);
        // …and settle the object receipts that are THE SAME PAPER as one of this act's (B-04). Must
        // follow the ADDENDUM: it is that estimate moving the money into «За договором» that takes
        // the receipt out of the «клієнт відшкодовує» receivable.
        receiptReconciler.reconcile(act);
        act.setStatus(WorkActStatus.SIGNED);
        act.setSignerName(req.signerName().trim());
        act.setSignedOffline(true);
        act.setSignedAt(Instant.now());
        List<WorkActItem> items = itemRepository.findByWorkActIdOrderBySortOrderAscIdAsc(id);
        List<WorkActPdfService.ReceiptRow> receipts = receiptRows(id);
        act.setDocHash(signedCopy.computeDocHash(act, items, receipts));
        signedCopy.emailClientCopy(act, items, receipts);
        return responseFactory.build(act);
    }

    /**
     * Owner-side status moves for an act the client did NOT sign (review fix — REJECTED was
     * unreachable, and with it a SENT act the client declines wedged the object forever: SENT can be
     * neither edited into deletion nor bypassed, and the one-open-act rule blocked every new act).
     *
     * <p>Allowed: SENT→DRAFT (recall to edit), SENT→REJECTED (the client declined — kept as
     * history), REJECTED→DRAFT (the client came around; re-enters the one-open-act rule, since
     * another act may have been opened in between). A SIGNED act stays immutable; everything else
     * is a 409. Going back to DRAFT clears {@code sentAt} and lets the share link 404 (the public
     * read only serves SENT/SIGNED).</p>
     */
    @Transactional
    public WorkActResponse changeStatus(UUID id, WorkActStatus target, UUID ownerId) {
        WorkAct act = loadOwnedForUpdate(id, ownerId);
        WorkActStatus from = act.getStatus();
        boolean allowed =
                (from == WorkActStatus.SENT
                        && (target == WorkActStatus.DRAFT || target == WorkActStatus.REJECTED))
                || (from == WorkActStatus.REJECTED && target == WorkActStatus.DRAFT);
        if (!allowed) {
            throw new WorkActConflictException("error.work-act.bad-transition", "WORK_ACT_BAD_TRANSITION");
        }
        if (from == WorkActStatus.REJECTED
                && workActRepository.existsByProjectIdAndStatusInAndIdNot(
                        act.getProject().getId(),
                        List.of(WorkActStatus.DRAFT, WorkActStatus.SENT), id)) {
            throw new WorkActConflictException("error.work-act.open-exists", "WORK_ACT_OPEN");
        }
        act.setStatus(target);
        if (target == WorkActStatus.DRAFT) {
            act.setSentAt(null);
        }
        return responseFactory.build(act);
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * An act must carry at least one line to leave the DRAFT stage (review fix). Without this, an
     * empty act could be sent and signed — and a SIGNED act is immutable and undeletable, so an
     * empty FINAL act would permanently block the object from ever having a real act.
     */
    void requireItems(UUID actId) {
        // Receipts count as content (round 2): «фінальний акт з матеріалами» may bill nothing but
        // re-billed receipts — that is a legitimate, signable act.
        if (!itemRepository.existsByWorkActId(actId) && !receiptRepository.existsByWorkActId(actId)) {
            throw new WorkActValidationException("error.work-act.empty", "WORK_ACT_EMPTY");
        }
    }

    static WorkAct requireNotSigned(WorkAct act) {
        if (act.getStatus() == WorkActStatus.SIGNED) {
            throw new WorkActSignedException();
        }
        return act;
    }

    WorkAct loadOwned(UUID id, UUID ownerId) {
        return requireOwned(workActRepository.findById(id), id, ownerId);
    }

    /**
     * {@link #loadOwned} with the act row locked {@code FOR UPDATE} — what every write path uses
     * (B-60). The lock is the act's, not the line's or the receipt's, because what must not
     * interleave is a child write and the SIGNATURE, and the act row is the only row both touch.
     */
    WorkAct loadOwnedForUpdate(UUID id, UUID ownerId) {
        return requireOwned(workActRepository.findByIdForUpdate(id), id, ownerId);
    }

    private static WorkAct requireOwned(java.util.Optional<WorkAct> found, UUID id, UUID ownerId) {
        WorkAct act = found
                .orElseThrow(() -> new ResourceNotFoundException("Work act not found: " + id));
        if (!act.getProject().getOwner().getId().equals(ownerId)) {
            throw new AccessDeniedException("Work act does not belong to the current user");
        }
        return act;
    }

    /**
     * Mark the act itself modified when a CHILD row changed — a line, a receipt.
     *
     * <p>{@code @Version} only moves when the act row is written, so adding a 1 800 ₴ receipt left
     * it untouched: the version the client's portal page is holding would still look current while
     * «До сплати» had changed under him (B-61 hangs on this). Dirtying {@code updatedAt} is the
     * deterministic way to say it — it goes through the ordinary optimistic-lock UPDATE, unlike
     * {@code EntityManager.lock(act, OPTIMISTIC_FORCE_INCREMENT)}, which Hibernate may drop as a
     * downgrade on a row already held {@code PESSIMISTIC_WRITE} — and {@code updated_at} moving
     * when the document changes is true anyway.
     */
    static void touch(WorkAct act) {
        act.setUpdatedAt(Instant.now());
    }

    List<WorkActPdfService.ReceiptRow> receiptRows(UUID actId) {
        return receiptRepository.findByWorkActIdNewestFirst(actId).stream()
                .map(WorkActPdfService.ReceiptRow::from)
                .toList();
    }

    private static BigDecimal quantityZero() {
        return BigDecimal.ZERO.setScale(QUANTITY_SCALE, ROUNDING);
    }

    private static String trim(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
