package com.majstr.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One anonymous visit to a public page, as the visitor's own browser reports it.
 *
 * <p>The source fields are the visitor's stored FIRST touch, the same values registration sends
 * ({@link RegisterRequest}), so a channel's visits and its registrations meet on one key.</p>
 */
public record VisitRequest(
        /** {@code PAGEVIEW}, {@code LEAVE}, {@code CLICK} or one of the {@code REGISTER_*} steps. */
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
        @Size(max = 35) String language,
        /** CLICK: what was pressed; REGISTER_FIELD / REGISTER_ERROR: the field or error code. Never a typed value. */
        @Size(max = 60) String target,
        /** LEAVE: per cent of the page that reached the bottom of the screen. */
        @Min(0) @Max(100) Integer scrollDepth,
        /** LEAVE: seconds the page was actually on screen. */
        @Min(0) @Max(86_400) Integer engagedSeconds
) {
}
