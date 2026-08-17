package com.majstr.backend.dto;

import com.majstr.backend.entity.ActNumberFormat;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.UserTrade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String fullName,
        Set<Trade> trades,
        /** Master-invented trades (user_trade) — see {@link Trade} for the closed system set;
         *  ordered by the master's own arrangement. */
        List<UserTradeResponse> customTrades,
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
        String referralCode,
        // Document requisites (acts iteration) — all nullable; feed the profile form + act PDF.
        String legalName,
        String taxId,
        String legalAddress,
        String iban,
        String bankName,
        boolean vatPayer,
        String vatId,
        Short taxGroup,
        BigDecimal taxRate,
        String docCity,
        ActNumberFormat actNumberFormat
) {
    /** {@code customTrades} is a separate explicit query, deliberately NOT a lazy collection on
     *  {@link User} — this factory is called from many places (auth, profile, billing), and riding
     *  a lazy relation would repeat the same "must eager-fetch or it throws outside the session"
     *  trap {@code trades} already carries. A plain query the caller already had to make anyway
     *  (mirrors how the rest of this DTO is assembled) has no such failure mode. */
    public static UserResponse from(User user, List<UserTrade> customTrades) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                new LinkedHashSet<>(user.getTrades()),
                customTrades.stream().map(UserTradeResponse::from).toList(),
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
                user.getReferralCode(),
                user.getLegalName(),
                user.getTaxId(),
                user.getLegalAddress(),
                user.getIban(),
                user.getBankName(),
                user.isVatPayer(),
                user.getVatId(),
                user.getTaxGroup(),
                user.getTaxRate(),
                user.getDocCity(),
                user.getActNumberFormat()
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
