package com.majstr.backend.entity;

/** Where an object photo came from. */
public enum PhotoSource {
    /** Photo of a receipt whose lines were parsed into an estimate. Starts PRIVATE like any
     *  photo; can be shared with the client (payments-economy-portal iteration). */
    RECEIPT,
    /** A progress photo the master took; can be shared with the client. */
    MANUAL
}
