package com.majstr.backend.dto;

import java.time.Instant;

/** Per-user upgrade-intent snapshot for the admin user card. */
public record UpgradeUserActivity(
        long clicks,
        Instant lastClickAt,
        boolean interested,
        String interestReason,
        Instant interestAt
) {}
