package com.majstr.backend.entity;

/**
 * Lifecycle of a {@link Payment}, mapped from the monobank invoice statuses
 * (created/processing/hold/success/failure/reversed/expired). PRO is granted
 * only on {@link #SUCCESS}.
 */
public enum PaymentStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILURE,
    EXPIRED,
    REVERSED
}
