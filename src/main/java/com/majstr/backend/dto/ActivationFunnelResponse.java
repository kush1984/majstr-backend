package com.majstr.backend.dto;

/**
 * Aggregate activation funnel across masters (ROLE_USER) — where they drop off.
 * Raw counts; the admin page renders the percentages (vs registered and vs the
 * previous step). All values are computed with a handful of aggregate queries
 * (no per-user loop).
 *
 * <p>{@code shared} = published an object-level link (PORTAL/ECONOMY/ACT) OR minted a per-estimate
 * link, MESSAGE excluded, revoked links still counted. See
 * {@code MetricsService.sharedWithClientCount()} — figures recorded before the
 * analytics-shared-fix iteration counted only the per-estimate half and are NOT comparable.</p>
 */
public record ActivationFunnelResponse(
        long registered,
        long verifiedEmail,
        long withProject,
        long withEstimate,
        long shared,
        long withSigned
) {}
