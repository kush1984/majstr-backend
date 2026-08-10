package com.majstr.backend.entity;

/** Which insight list a dismissal belongs to — see {@code catalog_insight_dismissals.kind}. */
public enum CatalogInsightKind {
    /** A position masters invented that our defaults do not cover at all. */
    NEW_POSITION,
    /** A position our defaults DO cover, written differently — evidence our wording fails. */
    RENAMED_POSITION,
    /** One of our default positions that no estimate has ever used. */
    UNUSED_DEFAULT,
    /** A whole estimate template a master built for themselves. */
    NEW_TEMPLATE,
    /** A default position whose community median price has drifted from what we ship. */
    PRICE_DRIFT
}
