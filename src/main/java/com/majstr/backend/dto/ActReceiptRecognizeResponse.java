package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What was read off a receipt for the act's «Чеки та рахунки» dialog — the three footer fields and
 * nothing else. Nothing is persisted by the recognition: the values PREFILL the dialog and the
 * master corrects them before saving.
 *
 * <p>{@code recognized=false} is a soft outcome, not an error: the reader could not make sense of
 * the photo (or the call failed), so the dialog stays manual — «введіть суму вручну». {@code
 * amount} and {@code issuedAt} may be null even when {@code recognized=true} (a torn footer).</p>
 *
 * <p>There is deliberately no item list here any more (master decision, 2026-08-28). Carrying a
 * receipt's positions into the act billed one receipt two ways and put shop goods under «Додаткові
 * роботи»; the act needs the sum and the photo. Positions off a receipt live in the ESTIMATE
 * import, which is untouched.</p>
 */
public record ActReceiptRecognizeResponse(
        boolean recognized,
        String label,
        BigDecimal amount,
        LocalDate issuedAt
) {
    public static ActReceiptRecognizeResponse failed() {
        return new ActReceiptRecognizeResponse(false, null, null, null);
    }
}
