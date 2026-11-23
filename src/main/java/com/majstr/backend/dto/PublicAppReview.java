package com.majstr.backend.dto;

import java.time.Instant;
import java.util.List;

/**
 * A published rating of Majstr, as majstr.pro shows it (V218): his first name and initial, never
 * the full name, email or phone; his trades; and figures read live from his account, so a review
 * carries what makes it believable — how long and how much he has used the app.
 */
public record PublicAppReview(
        String authorName,
        List<String> trades,
        int rating,
        String text,
        int monthsInMajstr,
        long estimates,
        long signedEstimates,
        Instant publishedAt
) {}
