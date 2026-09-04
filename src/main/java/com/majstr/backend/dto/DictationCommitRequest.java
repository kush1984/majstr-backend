package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * The master-confirmed dictated lines (after review-screen edits), appended to an open estimate.
 * Same shape and same rules as {@link ReceiptItemsCommitRequest} — amounts are final, the catalog
 * is never touched, a SIGNED estimate is refused — but its own type on purpose: the two review
 * flows will not stay identical (this one already knows which catalog row a line came from), and
 * sharing a record signature between features is how a change to one silently reshapes the other.
 */
public record DictationCommitRequest(
        @NotEmpty @Size(max = 200) @Valid List<CommitItem> items
) {
    public record CommitItem(
            @NotBlank @Size(max = 255) String name,
            @NotNull Unit unit,
            @NotNull @DecimalMin("0.0") @Digits(integer = 12, fraction = 3) BigDecimal quantity,
            // STRICTLY positive — master decision 2026-09-04: empty/0/negative price → nothing is
            // saved. Belt-and-braces with the PWA's `hasBad` disable-on-unpriced rule, so a bypass
            // (offline replay, curl) also refuses.
            @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 13, fraction = 2) BigDecimal unitPrice,
            @NotNull ItemType type,
            @Size(max = 100) String category
    ) {}
}
