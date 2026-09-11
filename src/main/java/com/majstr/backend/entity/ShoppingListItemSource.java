package com.majstr.backend.entity;

/**
 * Where a shopping list row came from (V126). Mirrors {@code shopping_list_item_source_check}.
 */
public enum ShoppingListItemSource {
    /** Derived from an estimate by the material calculator; always carries a {@code sourceEstimateId}. */
    CALCULATOR,
    /** A MATERIALS line copied straight off an estimate, with no norm involved. */
    ESTIMATE,
    /** Typed by the master. */
    MANUAL
}
