package com.majstr.backend.dto;

/**
 * Aggregate activation funnel across masters (ROLE_USER) — where they drop off.
 * Raw counts; the admin page renders the percentages (vs registered and vs the
 * previous step). All values are computed with a handful of aggregate COUNT
 * queries (no per-user loop).
 */
public record ActivationFunnelResponse(
        long registered,
        long verifiedEmail,
        long withProject,
        long withEstimate,
        long shared,
        long withSigned
) {}
