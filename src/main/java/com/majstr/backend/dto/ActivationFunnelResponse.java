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
 *
 * <p>{@code verifiedEmail} is NOT a step of the chain any more (share-without-verification,
 * 2026-11-09): sharing no longer needs it, so the page reads registered → object → estimate → shared
 * → signed and shows the verified count beside it. {@code sharedUnverified} = the masters in
 * {@code shared} whose email is still unverified today — the effect of dropping the gate (a master
 * who shared and verified later is no longer in it).</p>
 */
public record ActivationFunnelResponse(
        long registered,
        long verifiedEmail,
        long withProject,
        long withEstimate,
        long shared,
        long withSigned,
        long sharedUnverified
) {}
