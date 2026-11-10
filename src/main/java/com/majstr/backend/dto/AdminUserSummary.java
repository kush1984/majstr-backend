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
        String referralSource,
        boolean emailVerified,
        Instant createdAt,
        Instant lastActiveAt,
        String lastDeviceType,
        String lastOs,
        long clientsCount,
        long projectsCount,
        long estimatesCount,
        long signedEstimatesCount,
        /** Made a PDF in the app but no estimate of his reached SENT or SIGNED — the admin list's ⚠️
         *  (iteration-first-experience): he works, and hands the paper over some other way. */
        boolean pdfWithoutShare
) {
    public static AdminUserSummary of(User user, long clients, long projects, long estimates, long signed,
                                      boolean pdfWithoutShare) {
        return new AdminUserSummary(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getCompanyName(),
                new LinkedHashSet<>(user.getTrades()),
                user.getPlan(),
                user.getRole(),
                user.getReferralSource(),
                user.isEmailVerified(),
                user.getCreatedAt(),
                user.getLastActiveAt(),
                user.getLastDeviceType(),
                user.getLastOs(),
                clients,
                projects,
                estimates,
                signed,
                pdfWithoutShare
        );
    }

    public static AdminUserSummary from(User user) {
        return of(user, 0, 0, 0, 0, false);
    }
}
