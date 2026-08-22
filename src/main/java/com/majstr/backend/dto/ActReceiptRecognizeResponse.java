package com.majstr.backend.dto;

import com.majstr.backend.dto.EstimateImportParseResponse.ParsedItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the model read off a receipt photo for the act's «Чеки та рахунки» dialog (act-receipts
 * round 2). Nothing is persisted by the recognition — the values PREFILL the dialog and the master
 * corrects them before «Додати чек».
 *
 * <p>{@code recognized=false} is a soft outcome, not an error: the model could not read the photo
 * (or the call failed), so the dialog stays manual — «введіть суму вручну». {@code amount} and
 * {@code issuedAt} may be null even when {@code recognized=true} (a torn footer). {@code items} is
 * non-empty only in {@code withItems} mode; each carries the same per-field {@code issues} the
 * estimate's receipt review uses, so an unreadable price is re-asked, never invented.</p>
 */
public record ActReceiptRecognizeResponse(
        boolean recognized,
        String label,
        BigDecimal amount,
        LocalDate issuedAt,
        List<ParsedItem> items
) {
    public static ActReceiptRecognizeResponse failed() {
        return new ActReceiptRecognizeResponse(false, null, null, null, List.of());
    }
}
