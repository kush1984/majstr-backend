package com.majstr.backend.dto;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Lightweight read-model for /api/admin/users. Excludes passwordHash
 * (never leaks). {@code trades} is a lazy collection, so callers must
 * build this inside a transaction (the listing endpoint is read-only TX).
 */
public record AdminUserSummary(
        UUID id,
        String email,
        String fullName,
        String companyName,
        Set<Trade> trades,
        Plan plan,
        Role role,
        Instant createdAt,
        Instant lastActiveAt
) {
    public static AdminUserSummary from(User user) {
        return new AdminUserSummary(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getCompanyName(),
                new LinkedHashSet<>(user.getTrades()),
                user.getPlan(),
                user.getRole(),
                user.getCreatedAt(),
                user.getLastActiveAt()
        );
    }
}
