package com.majstr.backend.dto;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.Trade;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Full per-master activation view for /api/admin/users/{id} — the funnel for one
 * contractor: clients, projects, estimates by status, whether they ever shared
 * or got a signature, catalog size, logo, and last activity. Never includes
 * passwordHash.
 */
public record AdminUserDetail(
        UUID id,
        String email,
        boolean emailVerified,
        Set<Trade> trades,
        Plan plan,
        Role role,
        Instant createdAt,
        Instant lastActiveAt,
        long clientsCount,
        long projectsCount,
        EstimateBreakdown estimates,
        boolean hasShareLink,
        boolean hasSigned,
        long catalogItemsCount,
        boolean hasLogo,
        Instant lastEstimateCreatedAt
) {
    /** Estimate counts split by status, plus the total. */
    public record EstimateBreakdown(long total, long draft, long sent, long signed, long rejected) {}
}
