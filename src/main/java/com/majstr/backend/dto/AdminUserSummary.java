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
 *
 * <p>The activity counts (clients/projects/estimates/signed) are folded in by
 * {@code AdminUserService} from grouped queries over the page — see {@link #of};
 * {@link #from} (used by the plan-change response, whose body isn't shown) leaves
 * them at zero.</p>
 */
public record AdminUserSummary(
        UUID id,
        String email,
        String fullName,
        String companyName,
        Set<Trade> trades,
        Plan plan,
        Role role,
        boolean emailVerified,
        Instant createdAt,
        Instant lastActiveAt,
        long clientsCount,
        long projectsCount,
        long estimatesCount,
        long signedEstimatesCount
) {
    public static AdminUserSummary of(User user, long clients, long projects, long estimates, long signed) {
        return new AdminUserSummary(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getCompanyName(),
                new LinkedHashSet<>(user.getTrades()),
                user.getPlan(),
                user.getRole(),
                user.isEmailVerified(),
                user.getCreatedAt(),
                user.getLastActiveAt(),
                clients,
                projects,
                estimates,
                signed
        );
    }

    public static AdminUserSummary from(User user) {
        return of(user, 0, 0, 0, 0);
    }
}
