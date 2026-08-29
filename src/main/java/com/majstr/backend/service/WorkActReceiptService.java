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
import com.majstr.backend.repository.WorkActReceiptRepository;
import com.majstr.backend.service.fiscal.FiscalQrService;
import com.majstr.backend.service.importer.ActReceiptExtractor;
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
 * label, an amount and (round 2) a MANDATORY photo of the paper. Recognition ({@link #recognize})
 * only prefills label / date / amount — the positions on the paper are never read into the act.
 *
 * <p>Since V115 a receipt also carries {@code returnedAmount}: the master bought nails for 2000,
 * used part of them and took the rest back to the shop for 500, so the client owes 1500. It is one
 * field on the purchase, capped at its amount — deliberately not a second «return receipt» row
 * (there is no such paper) and not a negative one (the cap is what keeps every downstream figure
 * non-negative).</p>
 *
 * <p>Carrying a receipt's line items into the act was removed (master decision, 2026-08-28: «для
 * чого ми зробили щоб позиції переносились? може цього взагалі не потрібно?»). It billed one
 * receipt two different ways, needed the {@code itemized} flag to stop it being billed twice, filed
 * hardware-store goods under «Додаткові роботи» — a section about agreed WORK — and left the master
 * looking at a greyed-out sum he could not explain. The flag and the queries that honour it STAY:
 * receipts created that way may already be frozen into a SIGNED act, and those must keep reading
 * exactly as they were signed. No new receipt can be {@code itemized} any more.</p>
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
     *  negative amount used to die on the DB CHECK as a 500).
     *
     *  <p>Zero, not 0.01, since the receipts-batch iteration: a photographed receipt is SAVED FIRST
     *  and priced afterwards (that is the whole answer to «з недостатньою швидкістю інтернету довго
     *  думає і додавати чек не хоче» — the paper is safe before any recognition runs). An unpriced
     *  receipt is therefore a normal intermediate state, and what protects the document is
     *  {@link ActReceiptCompleteness}: an act holding one can be neither published nor signed.</p> */
    private static final BigDecimal MIN_AMOUNT = BigDecimal.ZERO;
    /** Default label for a receipt added with none — «Чек №1», «Чек №2»… The number is the
     *  receipt's own position at creation time and stays in the label, editable, even when the
     *  list re-sorts by date; the server owns it because the client cannot know how many receipts
     *  the act already holds (a batch upload would otherwise name every photo «Чек №1»). */
    private static final String DEFAULT_LABEL_PREFIX = "Чек №";
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

    private final WorkActReceiptRepository receiptRepository;
    private final WorkActService actService;
    private final StorageService storage;
    private final ActReceiptExtractor recognizer;
    private final ProjectPhotoService photoService;
    private final FiscalQrService fiscalQr;

    @Transactional(readOnly = true)
    public List<WorkActReceiptResponse> list(UUID actId, UUID ownerId) {
        actService.loadOwned(actId, ownerId);
        return receiptRepository.findByWorkActIdNewestFirst(actId).stream()
                .map(WorkActReceiptResponse::from)
                .toList();
    }

    /**
     * Attach a receipt. The photo is mandatory; everything else may still be unknown — a batch of
     * photos is saved first and enriched afterwards (receipts-batch iteration), so a zero amount is
     * accepted here and blocked later, at publish and sign, by {@link ActReceiptCompleteness}.
     *
     * <p>{@code requestedId} makes the create idempotent, the same shape as every other
     * offline-capable create in this codebase ({@code ClientService.create}): the client-generated
     * UUID becomes the row's id, so a retried upload — and a batch over a weak connection retries
     * for exactly the reason a queue replays — returns the receipt that already landed instead of
     * billing the material twice (in the act AND in its ADDENDUM rollup).</p>
     */
    @Transactional
    public WorkActReceiptResponse add(UUID actId, UUID ownerId, UUID requestedId, MultipartFile file,
                                      String label, BigDecimal amount, LocalDate issuedAt,
                                      boolean saveToPhotos) throws IOException {
        WorkAct act = WorkActService.requireNotSigned(actService.loadOwned(actId, ownerId));
        if (requestedId != null) {
            var existing = receiptRepository.findById(requestedId);
            if (existing.isPresent()) {
                WorkActReceipt r = existing.get();
                // Bound to THIS act, which is already owner-checked above — a replay naming a
                // foreign act is a 404, never a peek at somebody else's receipt.
                if (!r.getWorkAct().getId().equals(actId)) {
                    throw new ResourceNotFoundException("Receipt not found: " + requestedId);
                }
                return WorkActReceiptResponse.from(r); // idempotent replay
            }
        }
        // The photo is the receipt's proof — mandatory (round 2, master decision; a receipt row
        // with no paper behind it is just a number anyone could type).
        if (file == null || file.isEmpty()) {
            throw new WorkActValidationException(
                    "error.work-act.receipt-photo-required", "WORK_ACT_RECEIPT_PHOTO_REQUIRED");
        }
        requireValidFields(label, amount);
        if (receiptRepository.countByWorkActId(actId) >= MAX_RECEIPTS) {
            throw new WorkActValidationException("error.work-act.receipts-limit", "WORK_ACT_RECEIPTS_LIMIT");
        }
        byte[] content = file.getBytes();
        ImageKind kind = requireImage(content);
        int sortOrder = receiptRepository.maxSortOrder(actId) + 1;
        String resolvedLabel = (label == null || label.isBlank())
                ? DEFAULT_LABEL_PREFIX + (sortOrder + 1)
                : label.trim();
        WorkActReceipt receipt = receiptRepository.save(WorkActReceipt.builder()
                .id(requestedId)
                .workAct(act)
                .label(resolvedLabel)
                .amount(amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .issuedAt(issuedAt)
                .storageKey(storeBytes(content, kind))
                .sortOrder(sortOrder)
                .build());
        if (saveToPhotos) {
            // A SECOND copy into the object's Фото tab («Чеки» folder, photo-folders) — the act
            // keeps its own frozen copy, so the gallery one can be deleted or re-filed freely.
            // Fail-soft, and swallowed HERE: the copy runs in its own transaction (REQUIRES_NEW),
            // so the photo cap or a storage hiccup can never cost the master the receipt itself.
            try {
                photoService.saveReceiptCopy(act.getProject().getId(), ownerId, content, kind, resolvedLabel);
            } catch (IOException | RuntimeException e) {
                log.info("Receipt photo copy skipped for act {}: {}", actId, e.getMessage());
            }
        }
        return WorkActReceiptResponse.from(receipt);
    }

    /**
     * Read a receipt photo for the dialog: date + total (+ label) prefilled by the model. Persists
     * NOTHING. A model that cannot read the photo is a SOFT outcome ({@code recognized=false} →
     * «введіть вручну»), not an error — the receipt is still addable by hand.
     *
     * <p><b>Free, and gated by nothing.</b> Reading a footer is what turns a photographed slip into
     * a receipt row, and a FREE master can already file the photo; the item pass that used to sit
     * behind {@code Feature.RECEIPT_IMPORT} is gone with the transfer itself (see the class
     * javadoc). Reading a receipt INTO AN ESTIMATE is a different feature and keeps its gate. What
     * bounds this endpoint is {@link com.majstr.backend.service.ReceiptScanRateLimiter}, per
     * account, because it stays the first model call a FREE plan can reach at all.</p>
     *
     * <p>Deliberately NOT @Transactional: a vision call runs for seconds, and holding a pooled
     * connection open across it starves the pool. Ownership and the not-signed guard each run in
     * their own short transaction up front, so a foreign or frozen act never spends a model call —
     * the same shape as {@code ReceiptImportService.parse}.</p>
     */
    public ActReceiptRecognizeResponse recognize(
            UUID actId, UUID ownerId, MultipartFile file) throws IOException {
        if (actService.get(actId, ownerId).status() == WorkActStatus.SIGNED) {
            throw new WorkActSignedException();
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
        return runRecognition(content);
    }

    private ActReceiptRecognizeResponse runRecognition(byte[] content) {
        ImageKind kind = ImageContentTypeDetector.detect(
                Arrays.copyOf(content, Math.min(HEADER_PEEK_BYTES, content.length)));
        try {
            var read = recognizer.extractMeta(kind.contentType, content);
            return new ActReceiptRecognizeResponse(true, read.label(), read.total(), read.issuedAt());
        } catch (AiExtractionException e) {
            log.info("Act receipt recognition fell back to manual entry: {}", e.getMessage());
            return ActReceiptRecognizeResponse.failed();
        }
    }

    /**
     * Read a receipt from its printed fiscal QR code — the exact alternative to reading the photo.
     *
     * <p>Free, and deliberately gated by nothing (master decision, 2026-08-23): no model runs here,
     * so nothing on this path is a paid capability.
     *
     * <p>The ДПС lookup is skipped outright ({@code read(payload, false)}): it only adds the seller
     * name and the positions, and the act no longer carries positions at all, so a purely local
     * read is instant and independent of a third party's latency — which is what makes it safe to
     * fire automatically on every photo of a batch.
     *
     * <p>Not {@code @Transactional}: same reason as {@link #recognize}.
     */
    public ActReceiptRecognizeResponse readQr(UUID actId, UUID ownerId, String payload) {
        if (actService.get(actId, ownerId).status() == WorkActStatus.SIGNED) {
            throw new WorkActSignedException();
        }
        return fiscalQr.read(payload, false)
                .map(r -> new ActReceiptRecognizeResponse(true, r.label(), r.total(), r.issuedAt()))
                .orElseGet(ActReceiptRecognizeResponse::failed);
    }

    /**
     * Recognize a receipt that is ALREADY stored — what the «✨ Розпізнати» button on a saved
     * receipt card calls (receipts-batch iteration).
     *
     * <p>Same contract as {@link #recognize}: persists nothing, the client applies the prefill and
     * PATCHes. The difference is which bytes are read. The master's complaint was that recognition
     * over a weak connection takes long enough that adding the receipt fails altogether; the answer
     * is that the photo is uploaded ONCE, when the receipt is created, and every later read runs
     * against the stored copy — so a slow read can be abandoned, retried, or resumed after a page
     * reload without spending the master's uplink again.</p>
     */
    public ActReceiptRecognizeResponse recognizeStored(
            UUID actId, UUID receiptId, UUID ownerId) throws IOException {
        if (actService.get(actId, ownerId).status() == WorkActStatus.SIGNED) {
            throw new WorkActSignedException();
        }
        ProjectPhotoService.PhotoFile photo = readOwnedFile(actId, receiptId, ownerId);
        return runRecognition(photo.bytes());
    }

    private static void requireValidFields(String label, BigDecimal amount) {
        requireValidFields(label, amount, BigDecimal.ZERO);
    }

    private static void requireValidFields(String label, BigDecimal amount, BigDecimal returned) {
        // A blank label is not an error — the server names the receipt «Чек №N». Only an over-long
        // one is, since the column would truncate it.
        if (label != null && label.trim().length() > MAX_LABEL) {
            throw new WorkActValidationException(
                    "error.work-act.receipt-invalid", "WORK_ACT_RECEIPT_INVALID");
        }
        if (amount == null || amount.compareTo(MIN_AMOUNT) < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new WorkActValidationException(
                    "error.work-act.receipt-invalid", "WORK_ACT_RECEIPT_INVALID");
        }
        if (returned.signum() < 0) {
            throw new WorkActValidationException(
                    "error.work-act.receipt-invalid", "WORK_ACT_RECEIPT_INVALID");
        }
        // The cap is what keeps every downstream figure non-negative — the ADDENDUM line, the
        // MATERIALS expense and «Прийнято актами» all bill (amount - returned), so none of them
        // ever needs a signed value. Its own code, because it is reachable by lowering the AMOUNT
        // under an existing return, and «сума неправильна» would say nothing about which one.
        if (returned.compareTo(amount) > 0) {
            throw new WorkActValidationException(
                    "error.work-act.receipt-return-too-big", "WORK_ACT_RECEIPT_RETURN_TOO_BIG");
        }
    }

    /**
     * Edit a saved receipt. This is also the ONE door a return comes in through (V115): the master
     * takes leftover material back to the shop days after photographing the slip, so a return is
     * typed on the receipt he already has, never added as a second document — there is no paper for
     * it worth photographing. {@code amount} keeps saying what the receipt says, so it still matches
     * the photo the client can open; {@code returnedAmount} is subtracted from what is billed.
     */
    @Transactional
    public WorkActReceiptResponse update(UUID actId, UUID receiptId, UUID ownerId, WorkActReceiptRequest req) {
        WorkActService.requireNotSigned(actService.loadOwned(actId, ownerId));
        WorkActReceipt receipt = load(actId, receiptId);
        BigDecimal amount = req.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal returned = req.returnedOrZero().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        requireValidFields(req.label(), amount, returned);
        receipt.setLabel(req.label().trim());
        receipt.setAmount(amount);
        receipt.setReturnedAmount(returned);
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
