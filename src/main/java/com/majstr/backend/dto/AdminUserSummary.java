package com.majstr.backend.dto;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight read-model for /api/admin/users. Excludes passwordHash
 * (never leaks) and lazy associations.
 */
public record AdminUserSummary(
        UUID id,
        String email,
        String fullName,
        String companyName,
        Trade trade,
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
                user.getTrade(),
                user.getPlan(),
                user.getRole(),
                user.getCreatedAt(),
                user.getLastActiveAt()
        );
    }
}
