package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.service.importer.EstimateExtractor.DocumentKind;

import java.math.BigDecimal;
import java.util.List;

/**
 * One file read for «Перенести в Majstr»: what kind of document it is, the object/client the
 * paper names (null = not written), and its positions — each already filed under a trade and a
 * catalog category where our library or the master's own catalog recognised it. A proposal; nothing
 * is written until the commit.
 */
public record TransferParseResponse(
        String fileName,
        DocumentKind kind,
        String objectName,
        String address,
        String clientName,
        String title,
        BigDecimal depositAmount,
        List<Line> items
) {
    /** {@code section} is the room/part the DOCUMENT puts it under; {@code trade}+{@code category}
     *  are where the CATALOG files it (null = not recognised → «Інше»). */
    public record Line(
            String name,
            Unit unit,
            BigDecimal quantity,
            BigDecimal unitPrice,
            ItemType type,
            String section,
            Trade trade,
            String category,
            List<String> issues
    ) {}
}
