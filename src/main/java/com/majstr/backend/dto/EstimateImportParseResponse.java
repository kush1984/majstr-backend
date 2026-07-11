package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of extracting an estimate from an uploaded Excel/CSV file or a photo via
 * the LLM — a proposal, never written to the DB (the source file is discarded).
 * The client shows it on the review screen, lets the master fix cells, tick which
 * positions also go to the catalog, resolve name conflicts, then posts the
 * confirmed list to {@code /estimates/import/commit}.
 *
 * <p>{@code depositAmount} is the завдаток the model found on the sheet (nullable).
 * Each row's {@code issues} list is non-empty when a value was unreadable/unrecognized
 * (e.g. {@code "unit"}, {@code "quantity"}, {@code "price"}) so the review screen can
 * highlight it — important for hand-written photos where a digit may be uncertain.</p>
 */
public record EstimateImportParseResponse(
        List<ParsedItem> items,
        BigDecimal depositAmount
) {
    public record ParsedItem(
            String name,
            Unit unit,
            BigDecimal quantity,
            BigDecimal unitPrice,
            ItemType type,
            String category,
            List<String> issues
    ) {}
}
