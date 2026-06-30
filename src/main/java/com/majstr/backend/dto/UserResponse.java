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
        Instant acknowledgedClientDataAt
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
                user.getAcknowledgedClientDataAt()
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
