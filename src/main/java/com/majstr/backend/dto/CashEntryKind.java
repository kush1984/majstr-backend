package com.majstr.backend.dto;

/**
 * Where a row of the cash feed actually lives (V135) — the client needs this to know what it may
 * edit and where to send the master who taps it.
 *
 * <p>{@link #PERSONAL} rows are the only ones this screen owns. The other two are the object's own
 * money, shown here and edited there: one record, never two.</p>
 */
public enum CashEntryKind {
    /** A row of {@code cash_entry} — the master's own, off-object. Editable here. */
    PERSONAL,
    /** A {@code payment_receipt} on one of his objects. Edited in that object's economy. */
    OBJECT_PAYMENT,
    /** An {@code object_expenses} row. Edited in that object's journal. */
    OBJECT_EXPENSE,
    /**
     * A {@code project_receipt} the client has not paid back yet (review B-33) — money that left the
     * master's pocket at the till and is recorded in no other table. Edited through
     * {@code ProjectReceiptService}, so the object's own rules still run; «відшкодовується» is not
     * touched from here, since that answer belongs to the object's receipts screen.
     */
    OBJECT_RECEIPT,
    /**
     * A {@code work_act_receipt} of a SIGNED act that does not post its receipts to expenses
     * (review B-33). READ-ONLY here: the row is frozen inside a signed act's {@code doc_hash}, and a
     * screen that let him retype it would either lie or invalidate the client's document.
     */
    ACT_RECEIPT
}
