package com.majstr.backend.dto;

/**
 * How many new default-catalog items are available to add (newer than the user
 * last synced, for their trades, not already in their catalog) — backs the
 * "Add new from catalog" preview ("Знайдено N нових позицій").
 */
public record TemplateUpdatesResponse(int available) {}
