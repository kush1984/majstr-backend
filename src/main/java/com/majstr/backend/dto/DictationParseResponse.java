package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Spoken text → a review proposal. Nothing is written; the text is discarded after the call.
 *
 * <p>Each row is the model's reading of one spoken position, already reconciled with the master's
 * OWN catalog: when a position matched, its name, unit, price, type and category come from the
 * catalog row and {@code catalogItemId} names it. What the master actually said stays in
 * {@code spokenName} so the review screen can show both — «сказано: поклеїти шпалери».</p>
 *
 * <p>{@code issues} uses the same tokens as the receipt review ({@code "unit"}, {@code "quantity"},
 * {@code "price"}) plus {@code "catalog"}, which is the one that matters here: <b>a position we
 * could not find in the catalog is flagged, never silently priced at 0 ₴</b>. A line the master
 * does not notice is a line he signs for nothing.</p>
 */
public record DictationParseResponse(
        List<DictationItem> items
) {
    public record DictationItem(
            String name,
            String spokenName,
            Unit unit,
            BigDecimal quantity,
            BigDecimal unitPrice,
            ItemType type,
            String category,
            /**
             * Trade of the matched catalog row (V125). Null on an unmatched row. Read by the review
             * sheet so the master sees under WHICH trade a matched position was filed («Монтаж
             * вентиляції» → «Сантехніка») — master feedback 2026-09-04: «в каталозі він під трейдом
             * сантехніка, чому тут не видно».
             */
            Trade trade,
            UUID catalogItemId,
            List<String> issues
    ) {}
}
