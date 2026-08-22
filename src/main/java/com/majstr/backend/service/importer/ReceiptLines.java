package com.majstr.backend.service.importer;

import com.majstr.backend.dto.EstimateImportParseResponse.ParsedItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Extracted receipt lines → the review shape ({@link ParsedItem} with per-field {@code issues}),
 * shared by the estimate's receipt import and the act's receipt recognition (act-receipts round 2)
 * — one normalization, so a unit or a price the model fumbled is flagged identically in both
 * review flows and the master is «перепитаний» the same way.
 */
public final class ReceiptLines {

    private ReceiptLines() {
    }

    public static List<ParsedItem> toParsedItems(List<EstimateExtractor.Extracted.Line> lines) {
        List<ParsedItem> items = new ArrayList<>(lines.size());
        for (EstimateExtractor.Extracted.Line line : lines) {
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
        return items;
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
}
