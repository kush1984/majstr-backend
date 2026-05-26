package com.majstr.backend.dto;

import com.majstr.backend.entity.EstimateShareLink;

import java.time.Instant;
import java.util.UUID;

public record ShareLinkResponse(
        UUID id,
        String token,
        String url,
        Instant createdAt,
        Instant expiresAt,
        boolean revoked
) {
    public static ShareLinkResponse from(EstimateShareLink link, String url) {
        return new ShareLinkResponse(
                link.getId(),
                link.getToken(),
                url,
                link.getCreatedAt(),
                link.getExpiresAt(),
                link.isRevoked()
        );
    }
}
