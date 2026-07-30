package com.majstr.backend.service.importer;

import com.majstr.backend.dto.EstimateImportParseResponse;
import com.majstr.backend.dto.EstimateImportParseResponse.ParsedItem;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.ReceiptItemsCommitRequest;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.CatalogImportException;
import com.majstr.backend.exception.EstimateSignedException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.EstimateService.ImportEstimateData.ImportItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
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

    /** Commit the confirmed receipt lines: append them to the estimate (SIGNED → 409). */
    public EstimateResponse commit(UUID ownerId, UUID estimateId, ReceiptItemsCommitRequest req) {
        featureGuard.requireFeature(loadUser(ownerId), Feature.RECEIPT_IMPORT);
        List<ImportItem> items = req.items().stream()
                .map(i -> new ImportItem(i.type(), i.name(), i.category(), i.unit(), i.quantity(), i.unitPrice()))
                .toList();
        EstimateResponse estimate = estimateService.appendItems(estimateId, items, ownerId);
        log.info("Receipt import for {} → estimate {} (+{} items)", ownerId, estimateId, items.size());
        return estimate;
    }

    // ---- extraction → review mapping ------------------------------------------

    private EstimateImportParseResponse toReview(EstimateExtractor.Extracted extracted) {
        List<ParsedItem> items = new ArrayList<>();
        for (EstimateExtractor.Extracted.Line line : extracted.items()) {
            Unit unit = UnitNormalizer.normalize(line.unit());
            ItemType type = parseType(line.type());
            BigDecimal quantity = line.quantity();
            BigDecimal unitPrice = line.unitPrice();

            List<String> issues = new ArrayList<>();
            if (unit == null) issues.add("unit");
            if (quantity == null || quantity.signum() <= 0) issues.add("quantity");
            if (unitPrice == null || unitPrice.signum() <= 0) issues.add("price");

            items.add(new ParsedItem(
                    line.name().trim(),
                    unit,
                    quantity,
                    unitPrice,
                    type,
                    blankToNull(line.category()),
                    issues));
        }
        // A receipt has no deposit.
        return new EstimateImportParseResponse(items, null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ItemType parseType(String raw) {
        if (raw == null) {
            return ItemType.MATERIAL; // a receipt is usually goods
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.startsWith("WORK") || s.contains("РОБОТ")) {
            return ItemType.WORK;
        }
        return ItemType.MATERIAL;
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
