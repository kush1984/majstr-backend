package com.majstr.backend.service;

import com.majstr.backend.dto.ActReceiptRecognizeResponse;
import com.majstr.backend.dto.WorkActReceiptRequest;
import com.majstr.backend.dto.WorkActReceiptResponse;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActReceipt;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.exception.AiExtractionException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.exception.WorkActSignedException;
import com.majstr.backend.exception.WorkActValidationException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import com.majstr.backend.service.importer.ActReceiptExtractor;
import com.majstr.backend.service.importer.ReceiptLines;
import com.majstr.backend.service.ImageContentTypeDetector.ImageKind;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Receipts and invoices attached to a work act («Чеки та рахунки», act-receipts iteration) — a
 * label, an amount and (round 2) a MANDATORY photo of the paper. By default the line items are NOT
 * carried into the act — the amount is re-billed as a whole; recognition ({@link #recognize}) can
 * prefill the amount/date, and in {@code withItems} mode the positions go into the act while the
 * receipt itself flips {@code itemized} so the money is never billed twice.
 *
 * <p>The photo is stored under an act-owned key, so an act keeps reading identically after the
 * object's photo gallery is cleaned up (frozen-copy rule). Every write goes through the act's
 * immutability guard — a SIGNED act cannot gain, lose or re-price a receipt, because the receipts
 * block is part of the {@code doc_hash}ed document.</p>
 *
 * <p>How the money lands is deliberate and lives elsewhere: {@link ActAddendumCreator} rolls the
 * receipts into the act's SIGNED ADDENDUM estimate at sign time (so «За договором» absorbs them) and
 * {@link com.majstr.backend.repository.WorkActReceiptRepository#sumSignedActReceipts} adds them to
 * «Прийнято актами». Both halves or neither — see that query's javadoc.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkActReceiptService {

    private static final String RECEIPT_PREFIX = "act-receipts";
    private static final int HEADER_PEEK_BYTES = 16;
    private static final long MAX_PHOTO_BYTES = 8L * 1024 * 1024;
    private static final int MONEY_SCALE = 2;
    private static final int MAX_RECEIPTS = 50;
    private static final int MAX_LABEL = 160;
    /** Mirrors {@link com.majstr.backend.dto.WorkActReceiptRequest}'s bounds — the multipart add
     *  path has no bean validation, so the same rules are enforced here by hand (round 2: a
     *  negative amount used to die on the DB CHECK as a 500). */
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

    private final WorkActReceiptRepository receiptRepository;
    private final WorkActService actService;
    private final StorageService storage;
    private final ActReceiptExtractor recognizer;
    private final FeatureGuard featureGuard;
    private final UserRepository userRepository;
    private final ProjectPhotoService photoService;

    @Transactional(readOnly = true)
    public List<WorkActReceiptResponse> list(UUID actId, UUID ownerId) {
        actService.loadOwned(actId, ownerId);
        return receiptRepository.findByWorkActIdOrderBySortOrderAscCreatedAtAsc(actId).stream()
                .map(WorkActReceiptResponse::from)
                .toList();
    }

    @Transactional
    public WorkActReceiptResponse add(UUID actId, UUID ownerId, MultipartFile file,
                                      String label, BigDecimal amount, LocalDate issuedAt,
                                      boolean itemized, boolean saveToPhotos) throws IOException {
        WorkAct act = WorkActService.requireNotSigned(actService.loadOwned(actId, ownerId));
        // The photo is the receipt's proof — mandatory (round 2, master decision; a receipt row
        // with no paper behind it is just a number anyone could type).
        if (file == null || file.isEmpty()) {
            throw new WorkActValidationException(
                    "error.work-act.receipt-photo-required", "WORK_ACT_RECEIPT_PHOTO_REQUIRED");
        }
        requireValidFields(label, amount);
        if (receiptRepository.findByWorkActIdOrderBySortOrderAscCreatedAtAsc(actId).size() >= MAX_RECEIPTS) {
            throw new WorkActValidationException("error.work-act.receipts-limit", "WORK_ACT_RECEIPTS_LIMIT");
        }
        byte[] content = file.getBytes();
        ImageKind kind = requireImage(content);
        WorkActReceipt receipt = receiptRepository.save(WorkActReceipt.builder()
                .workAct(act)
                .label(label.trim())
                .amount(amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .issuedAt(issuedAt)
                .storageKey(storeBytes(content, kind))
                .itemized(itemized)
                .sortOrder(receiptRepository.maxSortOrder(actId) + 1)
                .build());
        if (saveToPhotos) {
            // A SECOND copy into the object's Фото tab («Чеки» folder, photo-folders) — the act
            // keeps its own frozen copy, so the gallery one can be deleted or re-filed freely.
            // Fail-soft, and swallowed HERE: the copy runs in its own transaction (REQUIRES_NEW),
            // so the photo cap or a storage hiccup can never cost the master the receipt itself.
            try {
                photoService.saveReceiptCopy(act.getProject().getId(), ownerId, content, kind, label.trim());
            } catch (IOException | RuntimeException e) {
                log.info("Receipt photo copy skipped for act {}: {}", actId, e.getMessage());
            }
        }
        return WorkActReceiptResponse.from(receipt);
    }

    /**
     * Read a receipt photo for the dialog: date + total (+ label) prefilled by the model; with
     * {@code withItems} also every purchased position, review-shaped, to carry into the act.
     * Persists NOTHING. A model that cannot read the photo is a SOFT outcome ({@code
     * recognized=false} → «введіть вручну»), not an error — the receipt is still addable by hand.
     *
     * <p><b>The gate is per MODE, not per endpoint</b> (master decision, 2026-08-23). The meta pass
     * (label / date / total off the footer, haiku) is FREE: it is what turns a photographed slip
     * into a receipt row, and a FREE master can already file the photo. The {@code withItems} pass
     * (the item table, sonnet, the estimate importer's own prompt) stays behind
     * {@code Feature.RECEIPT_IMPORT} — reading a table is the expensive call and the one that
     * writes lines into the document. So the FREE half is checked only for the mode that needs it,
     * and {@link com.majstr.backend.service.ReceiptScanRateLimiter} caps the endpoint per account,
     * because this is the FIRST model call a FREE plan can reach at all.</p>
     *
     * <p>Deliberately NOT @Transactional: a vision call runs for seconds, and holding a pooled
     * connection open across it starves the pool. Ownership and the not-signed guard each run in
     * their own short transaction up front, so a foreign or frozen act never spends a model call —
     * the same shape as {@code ReceiptImportService.parse}.</p>
     */
    public ActReceiptRecognizeResponse recognize(
            UUID actId, UUID ownerId, MultipartFile file, boolean withItems) throws IOException {
        if (actService.get(actId, ownerId).status() == WorkActStatus.SIGNED) {
            throw new WorkActSignedException();
        }
        if (withItems) {
            featureGuard.requireFeature(userRepository.findById(ownerId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found: " + ownerId)),
                    Feature.RECEIPT_IMPORT);
        }
        if (file == null || file.isEmpty()) {
            throw new WorkActValidationException(
                    "error.work-act.receipt-photo-required", "WORK_ACT_RECEIPT_PHOTO_REQUIRED");
        }
        byte[] content = file.getBytes();
        if (content.length < 4 || content.length > MAX_PHOTO_BYTES) {
            throw new UnsupportedMediaTypeException(
                    content.length < 4 ? "error.upload.empty" : "error.upload.too-large");
        }
        ImageKind kind = ImageContentTypeDetector.detect(
                Arrays.copyOf(content, Math.min(HEADER_PEEK_BYTES, content.length)));
        try {
            var read = withItems
                    ? recognizer.extractWithItems(kind.contentType, content)
                    : recognizer.extractMeta(kind.contentType, content);
            return new ActReceiptRecognizeResponse(
                    true, read.label(), read.total(), read.issuedAt(),
                    ReceiptLines.toParsedItems(read.items()));
        } catch (AiExtractionException e) {
            log.info("Act receipt recognition fell back to manual entry: {}", e.getMessage());
            return ActReceiptRecognizeResponse.failed();
        }
    }

    private static void requireValidFields(String label, BigDecimal amount) {
        if (label == null || label.isBlank() || label.trim().length() > MAX_LABEL) {
            throw new WorkActValidationException(
                    "error.work-act.receipt-invalid", "WORK_ACT_RECEIPT_INVALID");
        }
        if (amount == null || amount.compareTo(MIN_AMOUNT) < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new WorkActValidationException(
                    "error.work-act.receipt-invalid", "WORK_ACT_RECEIPT_INVALID");
        }
    }

    @Transactional
    public WorkActReceiptResponse update(UUID actId, UUID receiptId, UUID ownerId, WorkActReceiptRequest req) {
        WorkActService.requireNotSigned(actService.loadOwned(actId, ownerId));
        WorkActReceipt receipt = load(actId, receiptId);
        receipt.setLabel(req.label().trim());
        receipt.setAmount(req.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        receipt.setIssuedAt(req.issuedAt());
        return WorkActReceiptResponse.from(receipt);
    }

    @Transactional
    public void delete(UUID actId, UUID receiptId, UUID ownerId) {
        WorkActService.requireNotSigned(actService.loadOwned(actId, ownerId));
        WorkActReceipt receipt = load(actId, receiptId);
        if (receipt.getStorageKey() != null) {
            tryDelete(receipt.getStorageKey());
        }
        receiptRepository.delete(receipt);
    }

    /** Owner download of a receipt photo — authenticated, never through {@code /api/files}. */
    @Transactional(readOnly = true)
    public ProjectPhotoService.PhotoFile readOwnedFile(UUID actId, UUID receiptId, UUID ownerId) throws IOException {
        actService.loadOwned(actId, ownerId);
        return readFile(load(actId, receiptId));
    }

    /** Read a receipt photo for the public act portal. The caller has already validated the {@code ?a=}
     *  token AND that the act is client-visible — here we only bind the receipt to that act. */
    @Transactional(readOnly = true)
    public ProjectPhotoService.PhotoFile readPublicFile(UUID actId, UUID receiptId) throws IOException {
        return readFile(load(actId, receiptId));
    }

    // ---- helpers ----------------------------------------------------------

    private ProjectPhotoService.PhotoFile readFile(WorkActReceipt receipt) throws IOException {
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

    private static ImageKind requireImage(byte[] content) {
        if (content.length < 4) {
            throw new UnsupportedMediaTypeException("error.upload.empty");
        }
        if (content.length > MAX_PHOTO_BYTES) {
            throw new UnsupportedMediaTypeException("error.upload.too-large");
        }
        byte[] header = Arrays.copyOf(content, Math.min(HEADER_PEEK_BYTES, content.length));
        return ImageContentTypeDetector.detect(header);
    }

    private String storeBytes(byte[] content, ImageKind kind) throws IOException {
        StoredObject stored = storage.store(new ByteArrayInputStream(content), content.length,
                RECEIPT_PREFIX, kind.extension, kind.contentType);
        return stored.key();
    }

    private WorkActReceipt load(UUID actId, UUID receiptId) {
        return receiptRepository.findByIdAndWorkActId(receiptId, actId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found: " + receiptId));
    }

    private void tryDelete(String key) {
        try {
            storage.delete(key);
        } catch (IOException e) {
            log.warn("Could not delete stored act receipt {}: {}", key, e.getMessage());
        }
    }
}
