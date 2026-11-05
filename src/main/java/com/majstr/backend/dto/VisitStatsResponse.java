package com.majstr.backend.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Anonymous visits to the public pages over {@code from..to} (Kyiv days, both inclusive).
 *
 * <p>A «visitor» is one browser on one day: the hash behind it changes every day by design, so the
 * same person on three days counts three times. Every per-source figure takes each visitor-day ONCE,
 * by its first row of the day, which keeps the source tables adding up to {@code visitors}.</p>
 *
 * <p>{@code registered} counts masters who signed up from {@code registeredFrom}, keyed by the first
 * touch they registered with, so a channel's two columns are comparable. {@code registeredFrom} is
 * {@code from}, or the first day any visit was counted if that is later: sign-ups from before the
 * counter existed have no visits to be compared with. A null {@code source} is a real bucket (no
 * referrer, no UTM), as in {@link SourceBreakdownResponse}.</p>
 */
public record VisitStatsResponse(
        LocalDate from,
        LocalDate to,
        LocalDate registeredFrom,
        long pageviews,
        long visitors,
        /** Visitor-days that opened /register. */
        long sawRegister,
        /** Visitor-days that typed into the registration form. */
        long startedRegister,
        long registered,
        List<Day> days,
        List<Channel> referrers,
        List<Channel> utm,
        List<Channel> entryPages,
        List<Page> pages,
        List<Share> devices,
        List<Share> systems,
        List<Share> browsers,
        List<Share> timeZones,
        List<Share> languages
) {

    public record Day(LocalDate day, long pageviews, long visitors, long registered) {}

    public record Channel(String source, long visitors, long registered) {}

    public record Page(String path, long pageviews, long visitors) {}

    public record Share(String value, long visitors) {}
}
