package com.majstr.backend.dto;

import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String fullName,
        Trade trade,
        String phone,
        String companyName,
        String logoUrl,
        Instant createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getTrade(),
                user.getPhone(),
                user.getCompanyName(),
                logoUrlFromKey(user.getLogoUrl()),
                user.getCreatedAt()
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
