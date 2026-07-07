package com.majstr.backend.dto;

/** Summary of a committed import: how many positions were created / price-updated /
 *  skipped (duplicates the master chose to keep as-is). */
public record CatalogImportCommitResponse(int created, int updated, int skipped) {}
