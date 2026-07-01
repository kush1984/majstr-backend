package com.majstr.backend.dto;

import java.util.List;

/**
 * One page of default catalog positions for the admin editor. {@code currentVersion}
 * is the catalog's highest {@code added_in_version} — a newly created position is
 * stamped {@code currentVersion + 1} so existing masters pick it up via
 * "Add new from library".
 */
public record AdminCatalogTemplatePage(
        List<AdminCatalogTemplateResponse> items,
        int page,
        int totalPages,
        long totalItems,
        int currentVersion
) {}
