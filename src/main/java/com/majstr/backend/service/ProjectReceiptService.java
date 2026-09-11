package com.majstr.backend.service;

import com.majstr.backend.dto.ProjectReceiptRequest;
import com.majstr.backend.dto.ProjectReceiptResponse;
import com.majstr.backend.dto.ProjectReceiptsResponse;
import com.majstr.backend.dto.ReceiptRecognizeResponse;
import com.majstr.backend.entity.ExpenseCategory;
import com.majstr.backend.entity.ExpenseSource;
import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.ProjectReceipt;
import com.majstr.backend.exception.AiExtractionException;
import com.majstr.backend.exception.ProjectReceiptValidationException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.ProjectReceiptRepository;
import com.majstr.backend.service.ImageContentTypeDetector.ImageKind;
import com.majstr.backend.service.fiscal.FiscalQrPayload;
import com.majstr.backend.service.fiscal.FiscalQrService;
import com.majstr.backend.service.importer.ActReceiptExtractor;
import com.majstr.backend.storage.StorageService;
import com.majstr.backend.storage.StoredObject;
import com.majstr.backend.storage.UnsupportedMediaTypeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * «Чеки обʼєкта» (V129) — the receipt the master photographs at the builders' merchant, filed
 * against the OBJECT rather than against any document.
 *
 * <p><b>The design goal is that the till involves no economics.</b> Camera, the sum fills itself,
 * save. Two taps, and nothing has been decided about whose money it was: a receipt is
 * {@code reimbursable} by default — «клієнт відшкодовує» — because in this trade material is mostly
 * bought with the client's money (master's ruling). Only an explicit tap («це моя витрата») turns
 * it into a cost, and that is the one place an {@link ObjectExpense} is created here. Flipping back
 * removes it again. The V126 rule that «the list never writes an expense» is therefore intact:
 * money still enters the economy only through a receipt, and now only through a deliberate one.</p>
 *
 * <p>Everything else is the shape {@link WorkActReceiptService} already proved on live paper:</p>
 * <ul>
 *   <li>the photo is <b>mandatory</b> — a receipt row with no paper behind it is a number anyone
 *       could type;</li>
 *   <li>the photo is <b>saved first and priced afterwards</b>, so amount 0 is a legal intermediate
 *       state (the whole answer to «на слабкому інтернеті чек додаватись не хоче»). Nothing here
 *       gates on it: unlike an act, an object receipt is not part of a document anyone signs, so a
 *       priceless one costs nobody anything — the screen just says how many still need a number;</li>
 *   <li>the create is <b>idempotent</b> on a client-supplied {@code X-Entity-Uuid}, or a retried
 *       upload bills the same material twice;</li>
 *   <li>recognition — QR first, vision second — <b>persists nothing</b> and never fails hard.</li>
 * </ul>
 *
 * <p><b>Positions are never read into anything.</b> The money on a receipt is its total; the lines
 * on the paper are the client's to read off the photo. That decision was made for act receipts on
 * 2026-08-28 and nothing here revisits it.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectReceiptService {

    private static final String RECEIPT_PREFIX = "object-receipts";
    private static final int HEADER_PEEK_BYTES = 16;
    private static final long MAX_PHOTO_BYTES = 8L * 1024 * 1024;
    private static final int MONEY_SCALE = 2;
    /** Higher than an act's 50: an act covers one stage, an object covers the whole renovation. */
    private static final int MAX_RECEIPTS = 100;
    private static final int MAX_LABEL = 160;
    private static final BigDecimal MIN_AMOUNT = BigDecimal.ZERO;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");
    /** The server names an unlabelled receipt, for the same reason the act does: a batch upload
     *  cannot know how many the object already holds and would name every photo «Чек №1». */
    private static final String DEFAULT_LABEL_PREFIX = "Чек №";

    private final ProjectReceiptRepository receiptRepository;
    private final ObjectExpenseRepository expenseRepository;
    private final ProjectService projectService;
    private final StorageService storage;
    private final ActReceiptExtractor recognizer;
    private final FiscalQrService fiscalQr;

    @Transactional(readOnly = true)
    public ProjectReceiptsResponse list(UUID projectId, UUID ownerId) {
        projectService.loadOwned(projectId, ownerId);
        return render(receiptRepository.findByProjectIdNewestFirst(projectId));
    }

    /**
     * Attach a receipt to the object. Only the photo is required; label, amount and date may all
     * still be unknown — the batch is saved before any of it is read.
     */
    @Transactional
    public ProjectReceiptResponse add(UUID projectId, UUID ownerId, UUID requestedId,
                                      MultipartFile file, String label, BigDecimal amount,
                                      LocalDate issuedAt) throws IOException {
        projectService.loadOwned(projectId, ownerId);
        if (requestedId != null) {
            var existing = receiptRepository.findById(requestedId);
            if (existing.isPresent()) {
                ProjectReceipt r = existing.get();
                // Bound to THIS object, which is already owner-checked — a replay naming a foreign
                // object is a 404, never a peek at somebody else's receipt.
                if (!r.getProjectId().equals(projectId)) {
                    throw new ResourceNotFoundException("Receipt not found: " + requestedId);
                }
                return ProjectReceiptResponse.from(r, false); // idempotent replay
            }
        }
        if (file == null || file.isEmpty()) {
            throw new ProjectReceiptValidationException(
                    "error.project-receipt.photo-required", "PROJECT_RECEIPT_PHOTO_REQUIRED");
        }
        BigDecimal resolvedAmount = amount == null ? BigDecimal.ZERO : amount;
        requireValidFields(label, resolvedAmount);
        if (receiptRepository.countByProjectId(projectId) >= MAX_RECEIPTS) {
            throw new ProjectReceiptValidationException(
                    "error.project-receipt.limit", "PROJECT_RECEIPT_LIMIT");
        }
        byte[] content = file.getBytes();
        ImageKind kind = requireImage(content);
        int sortOrder = receiptRepository.maxSortOrder(projectId) + 1;
        ProjectReceipt receipt = receiptRepository.save(ProjectReceipt.builder()
                .id(requestedId)
                .projectId(projectId)
                .label((label == null || label.isBlank())
                        ? DEFAULT_LABEL_PREFIX + (sortOrder + 1)
                        : label.trim())
                .amount(resolvedAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .issuedAt(issuedAt)
                .storageKey(storeBytes(content, kind))
                .sortOrder(sortOrder)
                .build());
        return ProjectReceiptResponse.from(receipt, false);
    }

    /**
     * Edit a receipt — and the ONE door «whose money is this» goes through.
     *
     * <p>{@code reimbursable} is three-valued: {@code null} leaves it alone, so the ordinary «I read
     * the sum off the paper» save carries no opinion. When it flips to {@code false} the receipt
     * posts a MATERIALS/RECEIPT {@link ObjectExpense} and remembers its id; flipping back deletes
     * that row. While it stays {@code false}, an edited amount, label or date is mirrored onto the
     * expense — the two are the same fact and must never drift.</p>
     */
    @Transactional
    public ProjectReceiptResponse update(UUID projectId, UUID receiptId, UUID ownerId,
                                         ProjectReceiptRequest req) {
        projectService.loadOwned(projectId, ownerId);
        ProjectReceipt receipt = load(projectId, receiptId);
        BigDecimal amount = req.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        requireValidFields(req.label(), amount);
        receipt.setLabel(req.label().trim());
        receipt.setAmount(amount);
        receipt.setIssuedAt(req.issuedAt());
        if (req.fiscalFn() != null && req.fiscalId() != null) {
            // Written once, when a QR read finally identifies the paper. Never cleared by an
            // ordinary edit: the identity belongs to the photo, not to the numbers beside it.
            receipt.setFiscalFn(req.fiscalFn());
            receipt.setFiscalId(req.fiscalId());
        }
        applyReimbursable(receipt, req.reimbursable());
        return ProjectReceiptResponse.from(receipt, false);
    }

    @Transactional
    public void delete(UUID projectId, UUID receiptId, UUID ownerId) {
        projectService.loadOwned(projectId, ownerId);
        ProjectReceipt receipt = load(projectId, receiptId);
        dropExpense(receipt);
        if (receipt.getStorageKey() != null) {
            tryDelete(receipt.getStorageKey());
        }
        receiptRepository.delete(receipt);
    }

    /**
     * Read a receipt from its printed fiscal QR — the free first rung, and the only one that can
     * identify the paper. No model runs and, with {@code withPositions=false}, no network call
     * either, so this can fire automatically on every photo of a batch.
     *
     * <p>Not {@code @Transactional}: same reason as {@link #recognize} — nothing is written and the
     * ownership check runs in its own short transaction.</p>
     */
    public ReceiptRecognizeResponse readQr(UUID projectId, UUID ownerId, String payload) {
        projectService.loadOwned(projectId, ownerId);
        return fiscalQr.read(payload, false)
                .map(r -> {
                    // The identity lives in the payload, not in what the lookup answered.
                    FiscalQrPayload qr = FiscalQrPayload.parse(payload).orElse(null);
                    return new ReceiptRecognizeResponse(true, r.label(), r.total(), r.issuedAt(),
                            qr == null ? null : qr.fn(), qr == null ? null : qr.id());
                })
                .orElseGet(ReceiptRecognizeResponse::failed);
    }

    /**
     * Read an already-stored receipt photo: label + date + total off the footer, nothing else.
     * Persists nothing, and a model that cannot read the photo is a SOFT outcome — the master types
     * the sum. Reads the STORED bytes, so a slow read can be abandoned and retried without spending
     * his uplink again (the receipts-batch lesson).
     *
     * <p>Deliberately not {@code @Transactional}: a vision call runs for seconds and holding a
     * pooled connection across it starves the pool.</p>
     */
    public ReceiptRecognizeResponse recognize(UUID projectId, UUID receiptId, UUID ownerId) throws IOException {
        ProjectPhotoService.PhotoFile photo = readOwnedFile(projectId, receiptId, ownerId);
        ImageKind kind = ImageContentTypeDetector.detect(Arrays.copyOf(
                photo.bytes(), Math.min(HEADER_PEEK_BYTES, photo.bytes().length)));
        try {
            var read = recognizer.extractMeta(kind.contentType, photo.bytes());
            return ReceiptRecognizeResponse.read(read.label(), read.total(), read.issuedAt());
        } catch (AiExtractionException e) {
            log.info("Object receipt recognition fell back to manual entry: {}", e.getMessage());
            return ReceiptRecognizeResponse.failed();
        }
    }

    /** Owner download of a receipt photo — authenticated, never through {@code /api/files}. */
    @Transactional(readOnly = true)
    public ProjectPhotoService.PhotoFile readOwnedFile(UUID projectId, UUID receiptId, UUID ownerId)
            throws IOException {
        projectService.loadOwned(projectId, ownerId);
        ProjectReceipt receipt = load(projectId, receiptId);
        if (receipt.getStorageKey() == null) {
            throw new ResourceNotFoundException("Receipt has no photo");
        }
        byte[] bytes;
        try (InputStream in = storage.open(receipt.getStorageKey())
                .orElseThrow(() -> new ResourceNotFoundException("Receipt file not found"))) {
            bytes = in.readAllBytes();
        }
        String contentType = storage.contentType(receipt.getStorageKey()).orElse("application/octet-stream");
        return new ProjectPhotoService.PhotoFile(bytes, contentType);
    }

    /** Sum of the receipts the client is expected to pay back — the FREE-visible receivable. */
    @Transactional(readOnly = true)
    public BigDecimal reimbursableTotal(UUID projectId) {
        return receiptRepository.sumReimbursable(projectId);
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Mark every receipt that shares a printed fiscal identity with an EARLIER one on this object.
     * A warning, never a block: a shop can legitimately reprint a slip, and only the master is
     * holding the paper. Covers fiscal receipts alone — a hand-written товарний чек has no identity
     * to compare, and that gap is real.
     */
    private ProjectReceiptsResponse render(List<ProjectReceipt> receipts) {
        List<ProjectReceipt> byAge = new ArrayList<>(receipts);
        byAge.sort(Comparator.comparing(ProjectReceipt::getCreatedAt)
                .thenComparing(ProjectReceipt::getSortOrder));
        Set<String> seen = new HashSet<>();
        Set<UUID> duplicates = new HashSet<>();
        for (ProjectReceipt r : byAge) {
            if (r.getFiscalFn() == null || r.getFiscalId() == null) {
                continue;
            }
            if (!seen.add(r.getFiscalFn() + "|" + r.getFiscalId())) {
                duplicates.add(r.getId());
            }
        }
        BigDecimal reimbursable = BigDecimal.ZERO;
        BigDecimal own = BigDecimal.ZERO;
        long unpriced = 0;
        List<ProjectReceiptResponse> items = new ArrayList<>(receipts.size());
        for (ProjectReceipt r : receipts) {
            if (r.isReimbursable()) {
                reimbursable = reimbursable.add(r.getAmount());
            } else {
                own = own.add(r.getAmount());
            }
            if (r.getAmount().signum() <= 0) {
                unpriced++;
            }
            items.add(ProjectReceiptResponse.from(r, duplicates.contains(r.getId())));
        }
        return new ProjectReceiptsResponse(items, reimbursable, own, unpriced);
    }

    private void applyReimbursable(ProjectReceipt receipt, Boolean requested) {
        boolean target = requested == null ? receipt.isReimbursable() : requested;
        if (target) {
            dropExpense(receipt);
            receipt.setReimbursable(true);
            return;
        }
        receipt.setReimbursable(false);
        ObjectExpense expense = receipt.getExpenseId() == null
                ? null
                : expenseRepository.findByIdAndObjectId(receipt.getExpenseId(), receipt.getProjectId())
                        .orElse(null);
        if (expense == null) {
            expense = ObjectExpense.builder()
                    .objectId(receipt.getProjectId())
                    .category(ExpenseCategory.MATERIALS)
                    .source(ExpenseSource.RECEIPT)
                    .build();
        }
        expense.setAmount(receipt.getAmount());
        expense.setNote(receipt.getLabel());
        expense.setSpentAt(receipt.getIssuedAt() == null ? LocalDate.now() : receipt.getIssuedAt());
        receipt.setExpenseId(expenseRepository.save(expense).getId());
    }

    /** Remove the expense this receipt created, if it still exists. Deleting it by hand from the
     *  journal is allowed — the FK is ON DELETE SET NULL — so a missing row is not an error. */
    private void dropExpense(ProjectReceipt receipt) {
        UUID expenseId = receipt.getExpenseId();
        receipt.setExpenseId(null);
        if (expenseId != null) {
            expenseRepository.findByIdAndObjectId(expenseId, receipt.getProjectId())
                    .ifPresent(expenseRepository::delete);
        }
    }

    private static void requireValidFields(String label, BigDecimal amount) {
        // A blank label is not an error — the server names the receipt «Чек №N». Only an over-long
        // one is, since the column would truncate it.
        if (label != null && label.trim().length() > MAX_LABEL) {
            throw new ProjectReceiptValidationException(
                    "error.project-receipt.invalid", "PROJECT_RECEIPT_INVALID");
        }
        if (amount == null || amount.compareTo(MIN_AMOUNT) < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new ProjectReceiptValidationException(
                    "error.project-receipt.invalid", "PROJECT_RECEIPT_INVALID");
        }
    }

    private static ImageKind requireImage(byte[] content) {
        if (content.length < 4) {
            throw new UnsupportedMediaTypeException("error.upload.empty");
        }
        if (content.length > MAX_PHOTO_BYTES) {
            throw new UnsupportedMediaTypeException("error.upload.too-large");
        }
        return ImageContentTypeDetector.detect(
                Arrays.copyOf(content, Math.min(HEADER_PEEK_BYTES, content.length)));
    }

    private String storeBytes(byte[] content, ImageKind kind) throws IOException {
        StoredObject stored = storage.store(new ByteArrayInputStream(content), content.length,
                RECEIPT_PREFIX, kind.extension, kind.contentType);
        return stored.key();
    }

    private ProjectReceipt load(UUID projectId, UUID receiptId) {
        return receiptRepository.findByIdAndProjectId(receiptId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found: " + receiptId));
    }

    private void tryDelete(String key) {
        try {
            storage.delete(key);
        } catch (IOException e) {
            log.warn("Could not delete stored object receipt {}: {}", key, e.getMessage());
        }
    }
}
