package com.majstr.backend.entity;

/**
 * Where a client's «Я оплатив» stands (V217). The column's CHECK lists the same values.
 */
public enum PaymentNoticeStatus {
    /** The receipt did not match, could not be read, or was not attached: waits for the master. */
    PENDING,
    /** In the object's money — written by the receipt check, or by the master's «Кошти прийшли». */
    RECORDED,
    /** The master said the money did not arrive. Nothing was written. */
    DISMISSED
}
