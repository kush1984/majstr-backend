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
        List<EstimateExtractor.Extracted.Line> items,
        /**
         * Why there are no {@link #items}, when there are none (review B-54, the B-23 remainder).
         *
         * <p>An empty list meant three different things and the caller could not tell them apart,
         * so «позицій у чеку немає» was shown for all of them — blaming the paper for an outage.
         * The master's next move differs: {@link PositionSource#LOOKUP} says the receipt really is
         * line-less, {@link PositionSource#DISABLED} and {@link PositionSource#UNAVAILABLE} both say
         * «сфотографуйте чек», and only the last of those is worth a different wording.</p>
         */
        PositionSource positionSource
) {
    /** Where the positions came from, or why they did not. */
    public enum PositionSource {
        /** The tax service answered, and these are its lines (possibly none). */
        LOOKUP,
        /** The lookup is switched off in configuration — how the tests stay off the tax service. */
        DISABLED,
        /** The lookup ran and did not answer: an outage, a shape change, a new captcha. */
        UNAVAILABLE,
        /** The caller asked for no positions ({@code withPositions = false}). */
        NOT_ASKED
    }

    /** The code's own values, with no lookup behind them. */
    public static FiscalReceipt fromCodeAlone(LocalDate issuedAt, BigDecimal total,
                                              PositionSource why) {
        return new FiscalReceipt(null, issuedAt, total, List.of(), why);
    }
}
