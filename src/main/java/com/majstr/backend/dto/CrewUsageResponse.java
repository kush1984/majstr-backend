package com.majstr.backend.dto;

/**
 * How many masters work WITH A CREW — or rather, how many have left evidence of it.
 *
 * <p>Until now that was an assumption («більшість працюють самі») carried in conversation and
 * nowhere in the data. The only footprint a бригадир leaves is the duplicate-with-markup: he keeps
 * the crew's sheet and sends the client a copy priced above it. So this counts masters who made
 * one.</p>
 *
 * <p><b>Every figure here is a FLOOR, and the admin screen says so.</b> A бригадир who prices the
 * client's sheet by hand, or marks positions up in place, leaves no duplicate and is invisible to
 * this count. It can only ever say «at least this many», never «this share works alone».</p>
 *
 * @param withMarkupCopy      masters (role USER) who have ever made a duplicate with a markup
 * @param withMarkupCopy30d   ...of them, those who made one in the last 30 days
 * @param withSignedMarkupCopy masters whose marked-up copy the client actually SIGNED — the crew
 *                            workflow completed, not merely tried
 * @param activeMasters       the denominator, the same one the activation funnel's first step uses
 *                            (all masters), so the two reports can be read side by side
 */
public record CrewUsageResponse(
        long withMarkupCopy,
        long withMarkupCopy30d,
        long withSignedMarkupCopy,
        long activeMasters
) {}
