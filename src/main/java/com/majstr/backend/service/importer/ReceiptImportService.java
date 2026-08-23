package com.majstr.backend.service.importer;

import com.majstr.backend.dto.EstimateImportParseResponse;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.ReceiptItemsCommitRequest;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.CatalogImportException;
import com.majstr.backend.exception.EstimateSignedException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.dto.EstimateImportParseResponse.ParsedItem;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.fiscal.FiscalQrService;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.EstimateService.ImportEstimateData.ImportItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Add line items to an open estimate from a receipt photo (store / terminal / hand-written)
 * via LLM vision — PRO-gated ({@code Feature.RECEIPT_IMPORT}). {@code parse} sends the photo
 * to {@link EstimateExtractor} (receipt prompt), normalizes units/types, and returns a
 * review proposal — the image is never persisted. {@code commit} appends the master-confirmed
 * lines to the estimate (via {@link EstimateService#appendItems}); a SIGNED estimate is
 * rejected (409). Unlike the estimate import, receipts never touch the catalog.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptImportService {

    private final FeatureGuard featureGuard;
    private final UserRepository userRepository;
    private final EstimateExtractor extractor;
    private final EstimateService estimateService;
    private final FiscalQrService fiscalQr;

    /** Parse a receipt photo into a review proposal. Nothing is written; the bytes are discarded. */
    public EstimateImportParseResponse parse(UUID ownerId, UUID estimateId,
                                             String filename, String contentType, byte[] bytes) {
        featureGuard.requireFeature(loadUser(ownerId), Feature.RECEIPT_IMPORT);
        // Ownership + not-signed checked up front so a signed/foreign estimate never spends a call.
        if (estimateService.get(estimateId, ownerId).status() == EstimateStatus.SIGNED) {
            throw new EstimateSignedException();
        }

        String imageMediaType = imageMediaType(filename, contentType);
        if (imageMediaType == null) {
            throw new CatalogImportException("error.import.unsupported");
        }
        return toReview(extractor.extractReceiptFromImage(imageMediaType, bytes));
    }

    /**
     * Parse a receipt from its printed fiscal QR code instead of its photo — exact data, no model
     * call, and therefore no feature gate (master decision, 2026-08-23).
     *
     * <p>A code we cannot read, or one the lookup could not turn into positions, is an error here
     * rather than a soft empty result: this flow exists only to produce lines, and an empty review
     * would read as «чек порожній» instead of «спробуйте фото». The act's dialog answers softly
     * instead, because there the total and date alone are already worth having.
     */
    public EstimateImportParseResponse parseQr(UUID ownerId, UUID estimateId, String payload) {
        if (estimateService.get(estimateId, ownerId).status() == EstimateStatus.SIGNED) {
            throw new EstimateSignedException();
        }
        List<ParsedItem> items = fiscalQr.read(payload)
                .map(r -> ReceiptLines.toParsedItems(r.items()))
                .orElseThrow(() -> new CatalogImportException("error.fiscal-qr.unreadable"));
        if (items.isEmpty()) {
            throw new CatalogImportException("error.fiscal-qr.no-items");
        }
        return new EstimateImportParseResponse(items, null);
    }

    /** Commit the confirmed receipt lines: append them to the estimate (SIGNED → 409). */
    public EstimateResponse commit(UUID ownerId, UUID estimateId, ReceiptItemsCommitRequest req) {
        // Deliberately ungated: the paid capability is READING a receipt photo, and since the QR
        // path is free (master decision, 2026-08-23) a gate here would hand a FREE master his own
        // receipt's positions and then refuse to add them. Appending lines is not a PRO feature —
        // the same lines can be typed one by one in the editor.
        List<ImportItem> items = req.items().stream()
                .map(i -> new ImportItem(i.type(), i.name(), i.category(), i.unit(), i.quantity(), i.unitPrice()))
                .toList();
        EstimateResponse estimate = estimateService.appendItems(estimateId, items, ownerId);
        log.info("Receipt import for {} → estimate {} (+{} items)", ownerId, estimateId, items.size());
        return estimate;
    }

    // ---- extraction → review mapping ------------------------------------------

    private EstimateImportParseResponse toReview(EstimateExtractor.Extracted extracted) {
        // A receipt has no deposit. Normalization shared with the act's receipt recognition.
        return new EstimateImportParseResponse(ReceiptLines.toParsedItems(extracted.items()), null);
    }

    /** The image media type to send to Claude, or null if this isn't a supported image upload. */
    private static String imageMediaType(String filename, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.equals("image/jpeg") || ct.equals("image/jpg")) return "image/jpeg";
        if (ct.equals("image/png")) return "image/png";
        if (ct.equals("image/webp")) return "image/webp";
        if (ct.equals("image/gif")) return "image/gif";
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        return null;
    }

    private User loadUser(UUID ownerId) {
        return userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + ownerId));
    }
}
