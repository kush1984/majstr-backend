package com.majstr.backend.entity;

/** Derived from {@link ProjectPayment#status(java.time.LocalDate)} — never stored. Distinct
 *  from {@link PaymentStatus}, which tracks a monobank billing invoice, not object money. */
public enum ProjectPaymentStatus {
    PLANNED,
    PARTIAL,
    RECEIVED,
    OVERDUE
}
