package com.majstr.backend.dto;

/**
 * Whether the material calculator has anything to say about this estimate — the signal the PWA uses
 * to show or HIDE the «Матеріали» entry point (V129 round 1).
 *
 * <p>Hiding it is the point. V127 ships norms for DRYWALL and nothing else, so for every other trade
 * the screen would open with an empty buying list. An absent feature is quieter than a
 * broken-looking one.</p>
 *
 * <p>One field on purpose. It used to carry {@code workLines}/{@code coveredLines} as well, so the
 * caller could hint at a partial answer — nothing ever read them, and they were counted off a
 * denominator the master has since rejected (see {@link MaterialCoverage}). Two places computing
 * «how complete is this» is two places that can disagree with the screen.</p>
 *
 * @param available true when at least one work line resolves to a norm — the entry point is shown
 */
public record MaterialAvailabilityResponse(boolean available) {}
