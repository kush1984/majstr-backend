package com.majstr.backend.dto;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.Trade;

import java.time.Instant;
import java.util.List;
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
        // Name, phone and company: who this actually is, and how to reach them. The screen used to
        // identify a master by email alone, which is the one detail you cannot ring when a paying
        // customer has a problem.
        String fullName,
        String phone,
        String companyName,
        boolean emailVerified,
        Set<Trade> trades,
        Plan plan,
        Role role,
        String referralSource,
        Instant createdAt,
        Instant lastActiveAt,
        String lastDeviceType,
        String lastOs,
        long clientsCount,
        long projectsCount,
        EstimateBreakdown estimates,
        boolean hasShareLink,
        boolean hasSigned,
        long catalogItemsCount,
        boolean hasLogo,
        Instant lastEstimateCreatedAt,
        UpgradeUserActivity upgrade,
        // Per-object lifetime estimate churn (created vs deleted) — surfaces the
        // delete→create bypass of the concurrent FREE cap without blocking it.
        List<ObjectEstimateChurn> estimateChurn,
        // Subscription auto-renew: state, masked card, and the last auto-charge
        // outcome (never the raw token).
        Instant planExpiresAt,
        boolean autoRenew,
        String cardMask,
        String lastAutoRenewStatus,
        Instant lastAutoRenewAt,
        // When this master activated the one-time self-serve PRO trial (null = never).
        Instant trialStartedAt,
        // Master→master referrals: who invited this master (email, null if none),
        // and how many they invited / of those paid.
        String referredByEmail,
        long invitedCount,
        long invitedPaidCount
) {
    /** Estimate counts split by status, plus the total. */
    public record EstimateBreakdown(long total, long draft, long sent, long signed, long rejected) {}

    /** One object: how many estimates were ever created / deleted in it. */
    public record ObjectEstimateChurn(String name, int created, int deleted) {}
}
