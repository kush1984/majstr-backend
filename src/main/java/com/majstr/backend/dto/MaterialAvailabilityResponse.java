package com.majstr.backend.dto;

/**
 * Whether the material calculator has anything to say about this estimate — the signal the PWA uses
 * to show or HIDE the «Матеріали» entry point (V129 round 1).
 *
 * <p>Hiding it is the point. V127 ships norms for DRYWALL only, so for every other trade the screen
 * would open with each position listed as a gap and an empty buying list. An absent feature is
 * quieter than a broken-looking one.</p>
 *
 * @param available   true when at least one work line resolves to a norm — the entry point is shown
 * @param workLines   how many lines were examined (PERCENT and MATERIAL lines are not among them)
 * @param coveredLines how many of those we can answer for, so the caller can hint at a partial answer
 */
public record MaterialAvailabilityResponse(
        boolean available,
        int workLines,
        int coveredLines
) {}
