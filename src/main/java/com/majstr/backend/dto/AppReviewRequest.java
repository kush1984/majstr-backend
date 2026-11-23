package com.majstr.backend.dto;

import com.majstr.backend.entity.AppReviewSource;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A master's rating of Majstr (V218). {@code publishConsent} only counts at 4–5 stars — a low
 * rating is feedback for us, never put on the landing page.
 */
public record AppReviewRequest(
        @NotNull @Min(1) @Max(5) Integer rating,
        @Size(max = 2000) String text,
        /** Null = from the profile. */
        AppReviewSource source,
        boolean publishConsent
) {}
