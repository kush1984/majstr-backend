package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One anonymous visit to a public page, as the visitor's own browser reports it.
 *
 * <p>The source fields are the visitor's stored FIRST touch, the same values registration sends
 * ({@link RegisterRequest}), so a channel's visits and its registrations meet on one key.</p>
 */
public record VisitRequest(
        /** {@code PAGEVIEW} or {@code REGISTER_STARTED}. */
        @NotBlank @Size(max = 20) String kind,
        /** The page shown, path only. */
        @NotBlank @Size(max = 100) String path,
        @Size(max = 100) String referrerHost,
        @Size(max = 100) String entryPath,
        @Size(max = 60) String utmSource,
        @Size(max = 60) String utmMedium,
        @Size(max = 100) String utmCampaign,
        /** The browser's IANA time zone, which is where «where from» comes from without an address. */
        @Size(max = 64) String timeZone,
        @Size(max = 35) String language
) {
}
