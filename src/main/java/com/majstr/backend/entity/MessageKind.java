package com.majstr.backend.entity;

/**
 * What a {@link ProjectMessage} is (V217). The column's CHECK lists the same values — a new one here
 * needs the constraint widened in the same migration.
 */
public enum MessageKind {
    /** A question off the portal, or anything sent through the message link. */
    MESSAGE,
    /** The client's «Я оплатив»: an amount and, usually, a receipt; see {@link PaymentNoticeStatus}. */
    PAYMENT
}
