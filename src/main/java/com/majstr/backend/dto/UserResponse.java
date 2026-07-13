package com.majstr.backend.dto;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String fullName,
        Set<Trade> trades,
        String phone,
        String companyName,
        String logoUrl,
        Plan plan,
        Role role,
        boolean emailVerified,
        Instant createdAt,
        // Null until the master consents / acknowledges — drive the PWA's
        // one-time privacy-consent and client-data prompts.
        Instant consentedToPrivacyAt,
        Instant acknowledgedClientDataAt,
        // When a billing-granted PRO ends (null = FREE, or admin-granted with no
        // expiry). Drives the PWA's "PRO активний до DD.MM" badge.
        Instant planExpiresAt,
        // Auto-renewal state + masked card for the profile "Підписка" section.
        // NEVER the raw token/walletId — display-safe only.
        boolean autoRenew,
        String cardMask,
        // When the one-time PRO trial was activated (null = never used). The PWA
        // shows the "try PRO free" button only when this is null and plan is FREE.
        Instant trialStartedAt,
        // This master's personal referral code — the PWA builds the invite link
        // majstr.pro/?ref=m-<referralCode> from it.
        String referralCode
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                new LinkedHashSet<>(user.getTrades()),
                user.getPhone(),
                user.getCompanyName(),
                logoUrlFromKey(user.getLogoUrl()),
                user.getPlan(),
                user.getRole(),
                user.isEmailVerified(),
                user.getCreatedAt(),
                user.getConsentedToPrivacyAt(),
                user.getAcknowledgedClientDataAt(),
                user.getPlanExpiresAt(),
                user.isAutoRenew(),
                user.getCardMask(),
                user.getTrialStartedAt(),
                user.getReferralCode()
        );
    }

    /**
     * {@code User#logoUrl} stores the storage key (e.g. {@code logos/abc.png}).
     * Clients want a URL they can {@code <img src>}, so we prefix the public
     * file endpoint.
     */
    private static String logoUrlFromKey(String key) {
        return (key == null || key.isBlank()) ? null : "/api/files/" + key;
    }
}
