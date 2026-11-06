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
        /** Visitor-days whose browser reported a UTC time zone: servers, not people. Left out of every
         *  other figure here and counted only in this one. */
        long likelyBots,
        List<Day> days,
        List<Channel> referrers,
        List<Channel> utm,
        List<Channel> entryPages,
        List<Page> pages,
        List<Share> devices,
        List<Share> systems,
        List<Share> browsers,
        List<Share> timeZones,
        List<Share> languages,
        /** How far each page was read, from its LEAVE rows. */
        List<PageReading> reading,
        List<Click> clicks,
        /** Registration form fields focused, by field name, in visitor-days. */
        List<Share> formFields,
        /** Visitor-days that pressed «Створити акаунт» with a form that passed its own checks. */
        long formSubmitted,
        /** What stopped a submit: {@code field:<name>} (the form's own check) or {@code server:<code>}. */
        List<Share> formErrors,
        /** Visitor-days by the Kyiv hour (0-23) and ISO weekday (1 = Monday) of their first visit. */
        List<Slot> byHour,
        List<Slot> byWeekday
) {

    /** Per page: visitor-days that left it, the median seconds on screen, and how many reached each quarter. */
    public record PageReading(String path, long visitors, long medianSeconds,
                              long reached25, long reached50, long reached75, long reached100) {}

    public record Click(String path, String target, long visitors, long clicks) {}

    public record Slot(int slot, long visitors) {}

    public record Day(LocalDate day, long pageviews, long visitors, long registered) {}

    public record Channel(String source, long visitors, long registered) {}

    public record Page(String path, long pageviews, long visitors) {}

    public record Share(String value, long visitors) {}
}
