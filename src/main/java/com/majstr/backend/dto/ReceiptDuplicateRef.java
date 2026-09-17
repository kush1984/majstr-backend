package com.majstr.backend.dto;

import java.util.UUID;

/**
 * Where the twin of this receipt is (review item B-04) — a read-path warning, never a stored fact
 * and never a block: a shop can legitimately reprint a slip, and only the master is holding it.
 *
 * <p>Replaces V129's bare {@code duplicate} boolean, which could only ever look inside one table and
 * so stayed silent on the case that actually costs money: the same paper photographed at the till
 * AND attached to an act. Carrying the sibling's identity means the screen can say «цей чек уже є в
 * акті № 7» instead of «схоже на дублікат», which is the difference between a warning a master can
 * act on and one he learns to ignore.</p>
 *
 * @param kind      which table the twin lives in
 * @param id        the twin's id, so the client can link straight to it
 * @param label     the twin's label, for a sentence that names the paper
 * @param actNumber the act's display number when {@code kind} is {@link ReceiptKind#ACT}, else null
 */
public record ReceiptDuplicateRef(ReceiptKind kind, UUID id, String label, String actNumber) {

    public static ReceiptDuplicateRef object(UUID id, String label) {
        return new ReceiptDuplicateRef(ReceiptKind.OBJECT, id, label, null);
    }

    public static ReceiptDuplicateRef act(UUID id, String label, String actNumber) {
        return new ReceiptDuplicateRef(ReceiptKind.ACT, id, label, actNumber);
    }
}
