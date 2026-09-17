package com.majstr.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Edit an existing receipt's text/money. The photo is set once at upload and never replaced — swap
 * it by deleting the row and adding it again.
 *
 * <p>{@code fiscalFn} / {@code fiscalId} are the printed identity read off the QR (B-04), the same
 * two fields {@link ProjectReceiptRequest} carries. They arrive here rather than at upload because
 * the photo is saved before anything is read off it, and they are what lets the server notice that
 * this paper is already filed against the object — and, at sign time, stop it being counted twice.
 * Unlike the fields above they are NOT three-valued state: sending them writes them, omitting them
 * leaves the identity alone, because it belongs to the photo and not to the numbers beside it.</p>
 */
public record WorkActReceiptRequest(
        @NotBlank @Size(max = 160) String label,
        @NotNull @DecimalMin("0.00") @DecimalMax("99999999.99") BigDecimal amount,
        /** Part of this receipt taken back to the shop (V115). {@code null} means zero — the request
         *  carries the row's whole state, exactly like the three fields above, so an old client that
         *  never sends it cannot leave a stale return behind. Must not exceed {@link #amount}. */
        @DecimalMin("0.00") @DecimalMax("99999999.99") BigDecimal returnedAmount,
        LocalDate issuedAt,
        @Size(max = 64) String fiscalFn,
        @Size(max = 64) String fiscalId
) {
    public BigDecimal returnedOrZero() {
        return returnedAmount == null ? BigDecimal.ZERO : returnedAmount;
    }
}
