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
    OBJECT_EXPENSE
}
