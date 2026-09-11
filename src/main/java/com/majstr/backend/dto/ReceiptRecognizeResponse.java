package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What was read off a receipt for a receipt dialog — the footer fields and nothing else. Nothing is
 * persisted by the recognition: the values PREFILL the dialog and the master corrects them before
 * saving. Shared by the act's «Чеки та рахунки» and the object's «Чеки» (V129); both read the same
 * paper the same way.
 *
 * <p>{@code recognized=false} is a soft outcome, not an error: the reader could not make sense of
 * the photo (or the call failed), so the dialog stays manual — «введіть суму вручну». {@code
 * amount} and {@code issuedAt} may be null even when {@code recognized=true} (a torn footer).</p>
 *
 * <p>{@code fiscalFn} / {@code fiscalId} are filled ONLY by the QR path — they are the printed
 * identity of the physical paper, and the object receipts carry them so the same slip photographed
 * twice can be spotted. A vision read never produces them; a hand-written товарний чек has none.</p>
 *
 * <p>There is deliberately no item list here any more (master decision, 2026-08-28). Carrying a
 * receipt's positions into the act billed one receipt two ways and put shop goods under «Додаткові
 * роботи»; the act needs the sum and the photo. Positions off a receipt live in the ESTIMATE
 * import, which is untouched.</p>
 */
public record ReceiptRecognizeResponse(
        boolean recognized,
        String label,
        BigDecimal amount,
        LocalDate issuedAt,
        String fiscalFn,
        String fiscalId
) {
    public static ReceiptRecognizeResponse failed() {
        return new ReceiptRecognizeResponse(false, null, null, null, null, null);
    }

    /** A vision read: everything but the printed fiscal identity, which only the QR carries. */
    public static ReceiptRecognizeResponse read(String label, BigDecimal amount, LocalDate issuedAt) {
        return new ReceiptRecognizeResponse(true, label, amount, issuedAt, null, null);
    }
}
