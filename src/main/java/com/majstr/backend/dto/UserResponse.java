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
                user.getLogoUrl(),
                user.getCreatedAt()
        );
    }
}
