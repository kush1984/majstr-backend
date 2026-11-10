package com.majstr.backend.dto;

/**
 * Aggregate activation funnel across masters (ROLE_USER) — where they drop off.
 * Raw counts; the admin page renders the percentages (vs registered and vs the
 * previous step). All values are computed with a handful of aggregate queries
 * (no per-user loop); the three handover sets come from {@code ClientHandoffs}.
 *
 * <p>The chain is registered → object → estimate → {@code handedToClient} → signed
 * (iteration-first-experience, 2026-11-09). {@code handedToClient} = {@code shared} ∪
 * {@code pdfMade}, a union of masters: {@code shared} = an estimate reached SENT or SIGNED (the link
 * really left, or our letter); {@code pdfMade} = made an estimate or act PDF in the app (counted
 * from V196 on — earlier PDFs left no trace). {@code clientDownloadedPdf} = masters whose client
 * downloaded a PDF from a link — beside the chain.</p>
 *
 * <p>{@code verifiedEmail} is NOT a step (share-without-verification): sharing does not need it.
 * {@code sharedUnverified} = the masters in {@code shared} whose email is still unverified today.</p>
 */
public record ActivationFunnelResponse(
        long registered,
        long verifiedEmail,
        long withProject,
        long withEstimate,
        long shared,
        long withSigned,
        long sharedUnverified,
        long pdfMade,
        long handedToClient,
        long clientDownloadedPdf
) {}
