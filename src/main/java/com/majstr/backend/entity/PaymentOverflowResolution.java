package com.majstr.backend.entity;

/**
 * How to resolve a receipt that overshoots its plan stage's remaining amount — the master is
 * asked, the app never decides on its own. See {@code PaymentService#addReceipt}.
 */
public enum PaymentOverflowResolution {
    /** Close this stage exactly at its remaining amount; the surplus opens/adds to the next
     *  unclosed plan stage as its own partial receipt. */
    TRANSFER,
    /** Raise this stage's planned amount to cover the whole received sum — one receipt, stage
     *  closes exactly. */
    INCREASE,
    /** Keep the plan amount as-is; the whole received sum posts to this stage, which now reads
     *  "received more than planned" (e.g. "7 000 з 5 000"). */
    RESERVE
}
