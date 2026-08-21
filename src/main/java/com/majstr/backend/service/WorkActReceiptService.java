package com.majstr.backend.service;

import com.majstr.backend.dto.WorkActReceiptRequest;
import com.majstr.backend.dto.WorkActReceiptResponse;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActReceipt;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.exception.WorkActValidationException;
import com.majstr.backend.repository.WorkActReceiptRepository;
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
 * label, an amount and an optional photo of the paper. The master's ask was explicitly NOT to carry
 * a receipt's line items into the act: the amount is typed once and re-billed as a whole.
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

    private final WorkActReceiptRepository receiptRepository;
    private final WorkActService actService;
    private final StorageService storage;

    @Transactional(readOnly = true)
    public List<WorkActReceiptResponse> list(UUID actId, UUID ownerId) {
        actService.loadOwned(actId, ownerId);
        return receiptRepository.findByWorkActIdOrderBySortOrderAscCreatedAtAsc(actId).stream()
                .map(WorkActReceiptResponse::from)
                .toList();
    }

    @Transactional
    public WorkActReceiptResponse add(UUID actId, UUID ownerId, MultipartFile file,
                                      String label, BigDecimal amount, LocalDate issuedAt) throws IOException {
        WorkAct act = WorkActService.requireNotSigned(actService.loadOwned(actId, ownerId));
        if (receiptRepository.findByWorkActIdOrderBySortOrderAscCreatedAtAsc(actId).size() >= MAX_RECEIPTS) {
            throw new WorkActValidationException("error.work-act.receipts-limit", "WORK_ACT_RECEIPTS_LIMIT");
        }
        String key = file == null || file.isEmpty() ? null : storePhoto(file);
        WorkActReceipt receipt = receiptRepository.save(WorkActReceipt.builder()
                .workAct(act)
                .label(label.trim())
                .amount(amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .issuedAt(issuedAt)
                .storageKey(key)
                .sortOrder(receiptRepository.maxSortOrder(actId) + 1)
                .build());
        return WorkActReceiptResponse.from(receipt);
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

    private String storePhoto(MultipartFile file) throws IOException {
        byte[] content = file.getBytes();
        if (content.length < 4) {
            throw new UnsupportedMediaTypeException("error.upload.empty");
        }
        if (content.length > MAX_PHOTO_BYTES) {
            throw new UnsupportedMediaTypeException("error.upload.too-large");
        }
        byte[] header = Arrays.copyOf(content, Math.min(HEADER_PEEK_BYTES, content.length));
        ImageKind kind = ImageContentTypeDetector.detect(header);
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
