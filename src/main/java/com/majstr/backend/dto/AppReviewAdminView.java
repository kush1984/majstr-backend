package com.majstr.backend.dto;

import com.majstr.backend.entity.AppReviewSource;
import com.majstr.backend.entity.AppReviewStatus;

import java.time.Instant;
import java.util.UUID;

/** One rating of Majstr on the admin's list (V218) — with who wrote it, to answer him. */
public record AppReviewAdminView(
        UUID id,
        UUID userId,
        String email,
        String fullName,
        String phone,
        int rating,
        String text,
        AppReviewSource source,
        boolean publishConsent,
        AppReviewStatus status,
        Instant createdAt
) {}
