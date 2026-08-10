package com.majstr.backend.entity;

/** A quick share layout for "Розбити на частки" — the master thinks in shares of the total,
 *  not absolute numbers. {@link #CUSTOM} takes explicit percents from the request instead. */
public enum PaymentSplitPreset {
    FIFTY_FIFTY,
    THIRTY_FORTY_THIRTY,
    THIRTY_THIRTY_FORTY,
    CUSTOM
}
