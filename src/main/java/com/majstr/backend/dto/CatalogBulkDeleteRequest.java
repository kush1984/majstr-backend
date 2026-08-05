package com.majstr.backend.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/**
 * The positions the master ticked for deletion.
 *
 * <p>Explicit ids rather than a filter ("delete everything in trade X"), even for «видалити все».
 * A filter sent to the server is a rule the master cannot see the result of before it runs, and the
 * one destructive action in the catalog is not the place for that: he deletes what is on his
 * screen, and the request says exactly which rows those were.</p>
 */
public record CatalogBulkDeleteRequest(
        @NotEmpty List<UUID> ids
) {}
