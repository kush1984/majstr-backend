package com.majstr.backend.entity;

/** Which shape a {@link CatalogUpdateNotice} row carries — see that class. */
public enum CatalogUpdateNoticeKind {
    /** A catalog migration added/removed positions — {@code positionsAdded}/{@code positionsRemoved}. */
    COUNT,
    /** A community price-drift apply — {@code positionName}/{@code oldPrice}/{@code newPrice}. */
    PRICE_DRIFT
}
