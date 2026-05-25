package com.majstr.backend.entity;

/**
 * Shared by CatalogItem and EstimateItem: the kind of line — labour (WORK)
 * or consumable (MATERIAL). Drives the subtotal grouping in estimates.
 */
public enum ItemType {
    WORK,
    MATERIAL
}
