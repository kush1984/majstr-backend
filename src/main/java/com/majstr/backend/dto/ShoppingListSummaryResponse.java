package com.majstr.backend.dto;

import java.util.UUID;

/**
 * One line of the home-screen «🛒 Купити» card: enough to render it without loading every list.
 * Archived lists and lists with nothing left to buy are not returned at all.
 */
public record ShoppingListSummaryResponse(
        UUID projectId,
        String projectName,
        int totalCount,
        int boughtCount
) {}
