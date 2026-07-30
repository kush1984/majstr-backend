package com.majstr.backend.entity;

/**
 * Where a master's catalog row came from — recorded at write time, never inferred.
 *
 * <p>Inferring it was the first attempt and it failed in production: "absent from the default
 * catalog" also describes every position we ever shipped and later deleted, so the V70-V73
 * cleanup turned hundreds of our own rows into apparent master inventions.
 */
public enum CatalogItemSource {
    /** Copied from {@code catalog_templates} — registration seeding or "Додати нові позиції". */
    LIBRARY,
    /** The master typed it into their own catalog. This is the one the insight screens care about. */
    MANUAL,
    /** Came in through the price-list import (file, paste or photo). */
    IMPORT
}
