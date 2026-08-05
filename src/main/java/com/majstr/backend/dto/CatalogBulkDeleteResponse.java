package com.majstr.backend.dto;

/**
 * How many positions actually went.
 *
 * <p>Reported rather than assumed: a replayed offline delete, or a row already gone from another
 * device, makes the number smaller than the list sent — and the master is told what happened
 * instead of a toast claiming a count nobody verified.</p>
 */
public record CatalogBulkDeleteResponse(int deleted) {}
