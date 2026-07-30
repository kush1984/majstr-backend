package com.majstr.backend.entity;

public enum Unit {
    M2,
    M,
    LINEAR_METER,
    PIECE,
    KG,
    HOUR,
    SET,
    M3,
    T,
    POINT,
    PERCENT,
    KM,
    /** День роботи — how small or open-ended jobs are actually quoted. Not 8 × HOUR. */
    DAY,
    /** Поверх — carrying material up a building with no lift is priced per floor. */
    FLOOR
}
