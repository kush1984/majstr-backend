package com.majstr.backend.service.fiscal;

import com.majstr.backend.service.importer.EstimateExtractor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A receipt as the tax service stores it — the exact counterpart of what the vision pass only ever
 * guesses at. Lines are carried in the importer's {@code Line} shape on purpose: it is the currency
 * {@link com.majstr.backend.service.importer.ReceiptLines} normalizes, so a QR-read receipt and a
 * photo-read one are flagged, unit-normalized and re-asked identically.
 */
public record FiscalReceipt(
        String label,
        LocalDate issuedAt,
        BigDecimal total,
        List<EstimateExtractor.Extracted.Line> items
) {}
