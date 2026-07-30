package com.majstr.backend.exception;

import lombok.Getter;

/**
 * A default-catalog position would duplicate one that already exists — not by exact text, but
 * by {@code CatalogNameKey}: same words, different punctuation, connectors or order.
 *
 * <p>The DB's unique index cannot catch this (it compares exact name + type + unit), which is
 * how the catalog accumulated 100+ duplicates that V71 then spent 498 lines collapsing by hand.
 * Adding one more by hand, one admin click at a time, would rebuild exactly that.
 *
 * <p>Carries the colliding name so the admin can see WHAT it clashes with and decide — rename
 * theirs, or edit ours. It is a stop sign, not a lock: nothing here prevents editing the
 * existing position instead.
 */
@Getter
public class DefaultCatalogDuplicateException extends RuntimeException {

    private final String existingName;

    public DefaultCatalogDuplicateException(String existingName) {
        super("Default catalog already has an equivalent position: " + existingName);
        this.existingName = existingName;
    }
}
