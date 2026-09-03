package com.majstr.backend.service.importer;

import com.majstr.backend.dto.DictationCommitRequest;
import com.majstr.backend.dto.DictationParseResponse;
import com.majstr.backend.dto.DictationParseResponse.DictationItem;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.exception.EstimateSignedException;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.EstimateService.ImportEstimateData.ImportItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Dictated (or typed) free text → estimate positions, reviewed by the master, then appended to an
 * open estimate. The parse writes nothing and the text is discarded; {@code commit} goes through
 * {@link EstimateService#appendItems} like the receipt import, so a SIGNED estimate is refused
 * there by the one guard every write path shares.
 *
 * <p><b>Ungated on purpose (cut 0).</b> There is no {@code Feature} check here — the point of this
 * cut is to find out whether dictation is worth having at all, and a PRO wall would answer a
 * different question. What bounds it is {@code DictationRateLimiter}, per account and per hour,
 * exactly as the receipt-scan pass is bounded.</p>
 *
 * <p><b>Spoken values win, the catalog fills the blanks.</b> A master who says «по 250 гривень»
 * means it; a master who says nothing about price gets his own catalog price, and when there is no
 * catalog row to take one from the line comes back flagged {@code "catalog"} rather than priced at
 * 0 ₴ behind his back.</p>
 *
 * <p>Deliberately NOT {@code @Transactional}: the model call must never pin a pooled connection.
 * The ownership and not-signed checks run in their own short transaction up front, and the catalog
 * is loaded only after the call returns — same shape as {@code ReceiptImportService.parse} and
 * {@code WorkActReceiptService.recognize}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictationService {

    private final DictationExtractor extractor;
    private final CatalogItemRepository catalogItemRepository;
    private final EstimateService estimateService;

    public DictationParseResponse parse(UUID ownerId, UUID estimateId, String text) {
        // Ownership + not-signed checked up front so a signed/foreign estimate never spends a call.
        if (estimateService.get(estimateId, ownerId).status() == EstimateStatus.SIGNED) {
            throw new EstimateSignedException();
        }

        List<DictationExtractor.Spoken> spoken = extractor.extract(text);
        if (spoken.isEmpty()) {
            return new DictationParseResponse(List.of());
        }
        List<CatalogItem> catalog = catalogItemRepository.findByOwnerIdOrderByNameAsc(ownerId);

        List<DictationItem> items = new ArrayList<>(spoken.size());
        for (DictationExtractor.Spoken line : spoken) {
            items.add(toItem(line, CatalogMatcher.match(line.name(), catalog)));
        }
        log.info("Dictation for {} → estimate {}: {} spoken, {} matched", ownerId, estimateId,
                items.size(), items.stream().filter(i -> i.catalogItemId() != null).count());
        return new DictationParseResponse(items);
    }

    /** Commit the confirmed dictated lines: append them to the estimate (SIGNED → 409). */
    public EstimateResponse commit(UUID ownerId, UUID estimateId, DictationCommitRequest req) {
        // Ungated for the same reason the receipt commit is: appending lines was never the paid
        // capability — the same lines can be typed one by one in the editor.
        List<ImportItem> items = req.items().stream()
                .map(i -> new ImportItem(i.type(), i.name(), i.category(), i.unit(), i.quantity(), i.unitPrice()))
                .toList();
        EstimateResponse estimate = estimateService.appendItems(estimateId, items, ownerId);
        log.info("Dictation commit for {} → estimate {} (+{} items)", ownerId, estimateId, items.size());
        return estimate;
    }

    // ---- spoken line + catalog row → one review row ---------------------------

    private static DictationItem toItem(DictationExtractor.Spoken line, Optional<CatalogItem> matched) {
        Unit spokenUnit = UnitNormalizer.normalize(line.unit());

        String name = matched.map(CatalogItem::getName).orElseGet(() -> line.name().trim());
        Unit unit = spokenUnit != null ? spokenUnit : matched.map(CatalogItem::getUnit).orElse(null);
        BigDecimal price = line.unitPrice() != null
                ? line.unitPrice()
                : matched.map(CatalogItem::getDefaultPrice).orElse(null);
        ItemType type = matched.map(CatalogItem::getType).orElseGet(() -> parseType(line.type()));
        String category = matched.map(CatalogItem::getCategory).orElse(null);

        List<String> issues = new ArrayList<>();
        if (matched.isEmpty()) issues.add("catalog");
        if (unit == null) issues.add("unit");
        if (line.quantity() == null || line.quantity().signum() <= 0) issues.add("quantity");
        if (price == null || price.signum() <= 0) issues.add("price");

        return new DictationItem(name, line.name().trim(), unit, line.quantity(), price, type,
                category, matched.map(CatalogItem::getId).orElse(null), issues);
    }

    /** A dictated estimate is normally a list of WORK — the opposite default to a receipt's. */
    private static ItemType parseType(String raw) {
        if (raw == null) {
            return ItemType.WORK;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.startsWith("MATERIAL") || s.contains("МАТЕРІАЛ")) {
            return ItemType.MATERIAL;
        }
        return ItemType.WORK;
    }
}
