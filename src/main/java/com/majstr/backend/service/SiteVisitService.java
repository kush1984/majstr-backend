package com.majstr.backend.service;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.dto.VisitRequest;
import com.majstr.backend.dto.VisitStatsResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anonymous visits to the public pages (V187): the beacon's way in and the admin report's way out.
 *
 * <p>No address and no cookie are stored. The visitor key hashes the address and the browser string
 * under a salt that lives for one Kyiv day and is deleted by {@link #purge()}, so a browser is counted
 * once a day and cannot be recognised afterwards, not even by us.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteVisitService {

    /** Rows older than this go: a year and a month, so a month can be read beside the same one last year. */
    static final int KEEP_DAYS = 400;
    static final int MAX_PERIOD_DAYS = 366;
    /** Rows per table; the long tail of hosts and time zones says nothing at this scale. */
    static final int TOP = 30;

    private static final Pattern TIME_ZONE = Pattern.compile("[A-Za-z_]+(?:/[A-Za-z0-9_+-]+){0,2}");
    private static final Pattern LANGUAGE = Pattern.compile("([A-Za-z]{2,3})(?:[-_][A-Za-z0-9]{1,8})*");
    private static final Set<String> KYIV_ALIASES = Set.of("Europe/Kiev", "Europe/Zaporozhye", "Europe/Uzhgorod");
    /** A click label: set on the element, or derived from a link's own path. */
    private static final Pattern TARGET = Pattern.compile("[a-z0-9:_#/.-]{1,60}");
    /** Seconds on screen beyond an hour are a tab left open, not reading. */
    static final int MAX_ENGAGED_SECONDS = 3600;
    /**
     * A person's browser in Ukraine reports its own time zone; a server reports UTC. Such rows are kept
     * but every report reads past them and counts them only as {@code likelyBots}.
     */
    static final String HUMAN = "coalesce(time_zone, '') NOT IN "
            + "('UTC', 'Etc/UTC', 'GMT', 'Etc/GMT', 'Universal', 'Etc/Universal', 'Zulu', 'Etc/Zulu')";
    private static final int TOP_CLICKS = 50;
    private static final SecureRandom RANDOM = new SecureRandom();

    enum Kind {
        PAGEVIEW, REGISTER_STARTED, LEAVE, CLICK, REGISTER_FIELD, REGISTER_SUBMIT, REGISTER_ERROR;

        boolean named() {
            return this == CLICK || this == REGISTER_FIELD || this == REGISTER_ERROR;
        }
    }

    private record DaySalt(LocalDate day, byte[] salt) {}

    private final JdbcTemplate jdbc;
    private final AtomicReference<DaySalt> salt = new AtomicReference<>();

    /**
     * Drops quietly whatever is not a person's event: a bot, an unknown kind, a malformed path, a click or
     * form step with no usable name, a LEAVE that measured nothing.
     */
    public void record(VisitRequest req, String ip, String userAgent) {
        VisitorAgent agent = VisitorAgent.of(userAgent);
        Kind kind = kind(req.kind());
        String path = FirstTouch.path(req.path());
        if (agent == null || kind == null || path == null) {
            return;
        }
        String target = kind.named() ? target(req.target()) : null;
        Integer depth = kind == Kind.LEAVE ? clamp(req.scrollDepth(), 100) : null;
        Integer seconds = kind == Kind.LEAVE ? clamp(req.engagedSeconds(), MAX_ENGAGED_SECONDS) : null;
        if ((kind.named() && target == null) || (kind == Kind.LEAVE && depth == null && seconds == null)) {
            return;
        }
        LocalDate day = LocalDate.now(LocalizationConfig.ZONE);
        jdbc.update("""
                INSERT INTO site_visit (day, kind, visitor, path, referrer_host, entry_path, utm_source, utm_medium,
                                        utm_campaign, device, os, browser, time_zone, language, target,
                                        scroll_depth, engaged_seconds)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                day, kind.name(), visitor(day, ip, userAgent), path,
                FirstTouch.host(req.referrerHost()), FirstTouch.path(req.entryPath()),
                trimToNull(req.utmSource()), trimToNull(req.utmMedium()), trimToNull(req.utmCampaign()),
                agent.device(), agent.os(), agent.browser(),
                timeZone(req.timeZone()), language(req.language()), target, depth, seconds);
    }

    /** The last {@code days} Kyiv days, today included. */
    public VisitStatsResponse stats(int days) {
        int span = Math.max(1, Math.min(days, MAX_PERIOD_DAYS));
        LocalDate to = LocalDate.now(LocalizationConfig.ZONE);
        LocalDate from = to.minusDays(span - 1L);
        LocalDate registeredFrom = registrationsFrom(from,
                jdbc.queryForObject("SELECT min(day) FROM site_visit", LocalDate.class));
        Timestamp regFrom = Timestamp.from(registeredFrom.atStartOfDay(LocalizationConfig.ZONE).toInstant());
        Timestamp regTo = Timestamp.from(to.plusDays(1).atStartOfDay(LocalizationConfig.ZONE).toInstant());

        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE human AND kind = 'PAGEVIEW') AS pageviews,
                       count(DISTINCT day::text || visitor) FILTER (WHERE human) AS visitors,
                       count(DISTINCT day::text || visitor)
                           FILTER (WHERE human AND kind = 'PAGEVIEW' AND path = '/register') AS saw_register,
                       count(DISTINCT day::text || visitor) FILTER (WHERE human AND kind = 'REGISTER_STARTED') AS started,
                       count(DISTINCT day::text || visitor) FILTER (WHERE human AND kind = 'REGISTER_SUBMIT') AS submitted,
                       count(DISTINCT day::text || visitor) FILTER (WHERE NOT human) AS bots
                FROM (SELECT v.*, %s AS human FROM site_visit v WHERE day BETWEEN ? AND ?) visits
                """.formatted(HUMAN), from, to);
        long registered = count(jdbc.queryForObject("""
                SELECT count(*) FROM users WHERE role = 'USER' AND created_at >= ? AND created_at < ?
                """, Long.class, regFrom, regTo));

        Map<String, Map<String, Long>> visitorsBy = firstTouchShares(from, to);
        Map<String, Map<String, Long>> registeredBy = registrationsByFirstTouch(regFrom, regTo);

        Map<String, List<VisitStatsResponse.Slot>> slots = slots(from, to);

        return new VisitStatsResponse(from, to, registeredFrom,
                count(totals.get("pageviews")), count(totals.get("visitors")),
                count(totals.get("saw_register")), count(totals.get("started")), registered,
                count(totals.get("bots")),
                days(from, to, regFrom, regTo),
                channels(visitorsBy.get("referrer"), registeredBy.get("referrer")),
                channels(visitorsBy.get("utm"), registeredBy.get("utm")),
                channels(visitorsBy.get("entry"), registeredBy.get("entry")),
                pages(from, to),
                shares(visitorsBy.get("device")), shares(visitorsBy.get("os")), shares(visitorsBy.get("browser")),
                shares(visitorsBy.get("zone")), shares(visitorsBy.get("language")),
                reading(from, to), clicks(from, to),
                named(from, to, Kind.REGISTER_FIELD), count(totals.get("submitted")),
                named(from, to, Kind.REGISTER_ERROR),
                slots.get("hour"), slots.get("weekday"));
    }

    /**
     * Registrations are counted from the first day anything was counted, not from {@code from}: a month
     * of sign-ups set against one day of visits would read as a conversion of a thousand per cent.
     */
    static LocalDate registrationsFrom(LocalDate from, LocalDate firstVisitDay) {
        return firstVisitDay == null || firstVisitDay.isBefore(from) ? from : firstVisitDay;
    }

    /** Yesterday's salt goes the minute today starts, and with it any way back to yesterday's visitors. */
    @Scheduled(cron = "${app.cleanup.visits-cron:0 5 0 * * *}", zone = "Europe/Kyiv")
    public void purge() {
        LocalDate today = LocalDate.now(LocalizationConfig.ZONE);
        int salts = jdbc.update("DELETE FROM site_visit_salt WHERE day < ?", today);
        int visits = jdbc.update("DELETE FROM site_visit WHERE day < ?", today.minusDays(KEEP_DAYS));
        DaySalt cached = salt.get();
        if (cached != null && cached.day().isBefore(today)) {
            salt.compareAndSet(cached, null);
        }
        if (salts > 0 || visits > 0) {
            log.info("Visit cleanup removed {} day salts and {} visit rows", salts, visits);
        }
    }

    String visitor(LocalDate day, String ip, String userAgent) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(saltFor(day));
            sha.update(String.valueOf(ip).getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(userAgent.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from the JVM", e);
        }
    }

    /** One salt per day across restarts and instances: the first writer wins, everyone reads it back. */
    private byte[] saltFor(LocalDate day) {
        DaySalt cached = salt.get();
        if (cached != null && cached.day().equals(day)) {
            return cached.salt();
        }
        byte[] fresh = new byte[32];
        RANDOM.nextBytes(fresh);
        jdbc.update("INSERT INTO site_visit_salt (day, salt) VALUES (?, ?) ON CONFLICT (day) DO NOTHING", day, fresh);
        byte[] stored = jdbc.queryForObject("SELECT salt FROM site_visit_salt WHERE day = ?", byte[].class, day);
        salt.set(new DaySalt(day, stored));
        return stored;
    }

    /**
     * Each visitor-day once, by its first row, counted along every dimension in one pass. A NULL value
     * is a real bucket (no referrer, no UTM, no time zone), told apart from «not this dimension» by
     * {@code GROUPING}.
     */
    private Map<String, Map<String, Long>> firstTouchShares(LocalDate from, LocalDate to) {
        Map<String, Map<String, Long>> out = new HashMap<>();
        jdbc.query("""
                WITH firsts AS (
                    SELECT DISTINCT ON (day, visitor) referrer_host, utm_source, entry_path, device, os, browser,
                                                      time_zone, language
                    FROM site_visit WHERE day BETWEEN ? AND ? AND %s
                    ORDER BY day, visitor, occurred_at, id)
                SELECT CASE WHEN GROUPING(referrer_host) = 0 THEN 'referrer'
                            WHEN GROUPING(utm_source) = 0 THEN 'utm'
                            WHEN GROUPING(entry_path) = 0 THEN 'entry'
                            WHEN GROUPING(device) = 0 THEN 'device'
                            WHEN GROUPING(os) = 0 THEN 'os'
                            WHEN GROUPING(browser) = 0 THEN 'browser'
                            WHEN GROUPING(time_zone) = 0 THEN 'zone'
                            ELSE 'language' END AS dimension,
                       COALESCE(referrer_host, utm_source, entry_path, device, os, browser, time_zone, language)
                           AS value,
                       count(*) AS visitors
                FROM firsts
                GROUP BY GROUPING SETS ((referrer_host), (utm_source), (entry_path), (device), (os), (browser),
                                        (time_zone), (language))
                """.formatted(HUMAN), rs -> {
            out.computeIfAbsent(rs.getString("dimension"), d -> new HashMap<>())
                    .put(rs.getString("value"), rs.getLong("visitors"));
        }, from, to);
        return out;
    }

    /** Masters registered in the period, by the first touch they registered with. */
    private Map<String, Map<String, Long>> registrationsByFirstTouch(Timestamp from, Timestamp to) {
        Map<String, Map<String, Long>> out = new HashMap<>();
        jdbc.query("""
                SELECT CASE WHEN GROUPING(first_referrer_host) = 0 THEN 'referrer'
                            WHEN GROUPING(utm_source) = 0 THEN 'utm'
                            ELSE 'entry' END AS dimension,
                       COALESCE(first_referrer_host, utm_source, first_entry_path) AS value,
                       count(*) AS registered
                FROM users
                WHERE role = 'USER' AND created_at >= ? AND created_at < ?
                GROUP BY GROUPING SETS ((first_referrer_host), (utm_source), (first_entry_path))
                """, rs -> {
            out.computeIfAbsent(rs.getString("dimension"), d -> new HashMap<>())
                    .put(rs.getString("value"), rs.getLong("registered"));
        }, from, to);
        return out;
    }

    private List<VisitStatsResponse.Day> days(LocalDate from, LocalDate to, Timestamp regFrom, Timestamp regTo) {
        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            byDay.put(d, new long[3]);
        }
        jdbc.query("""
                SELECT day, count(*) FILTER (WHERE kind = 'PAGEVIEW') AS pageviews, count(DISTINCT visitor) AS visitors
                FROM site_visit WHERE day BETWEEN ? AND ? AND %s GROUP BY day
                """.formatted(HUMAN), rs -> {
            long[] row = byDay.get(rs.getObject("day", LocalDate.class));
            if (row != null) {
                row[0] = rs.getLong("pageviews");
                row[1] = rs.getLong("visitors");
            }
        }, from, to);
        jdbc.query("""
                SELECT (created_at AT TIME ZONE 'Europe/Kyiv')::date AS day, count(*) AS registered
                FROM users WHERE role = 'USER' AND created_at >= ? AND created_at < ? GROUP BY 1
                """, rs -> {
            long[] row = byDay.get(rs.getObject("day", LocalDate.class));
            if (row != null) {
                row[2] = rs.getLong("registered");
            }
        }, regFrom, regTo);
        List<VisitStatsResponse.Day> out = new ArrayList<>(byDay.size());
        byDay.forEach((day, row) -> out.add(new VisitStatsResponse.Day(day, row[0], row[1], row[2])));
        return out;
    }

    private List<VisitStatsResponse.Page> pages(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT path, count(*) AS pageviews, count(DISTINCT day::text || visitor) AS visitors
                FROM site_visit WHERE day BETWEEN ? AND ? AND kind = 'PAGEVIEW' AND %s
                GROUP BY path ORDER BY visitors DESC, pageviews DESC, path LIMIT ?
                """.formatted(HUMAN), (rs, i) -> new VisitStatsResponse.Page(rs.getString("path"), rs.getLong("pageviews"),
                rs.getLong("visitors")), from, to, TOP);
    }

    /**
     * Each page view's deepest scroll and longest time on screen. A visit that hid the tab and came back
     * sends LEAVE again with the running figures, so a view is its MAX, never a sum.
     */
    private List<VisitStatsResponse.PageReading> reading(LocalDate from, LocalDate to) {
        return jdbc.query("""
                WITH views AS (
                    SELECT path, day, visitor, max(scroll_depth) AS depth, max(engaged_seconds) AS seconds
                    FROM site_visit WHERE day BETWEEN ? AND ? AND kind = 'LEAVE' AND %s
                    GROUP BY path, day, visitor)
                SELECT path, count(*) AS visitors,
                       coalesce(percentile_cont(0.5) WITHIN GROUP (ORDER BY seconds), 0) AS median_seconds,
                       count(*) FILTER (WHERE depth >= 25) AS reached25,
                       count(*) FILTER (WHERE depth >= 50) AS reached50,
                       count(*) FILTER (WHERE depth >= 75) AS reached75,
                       count(*) FILTER (WHERE depth >= 100) AS reached100
                FROM views GROUP BY path ORDER BY visitors DESC, path LIMIT ?
                """.formatted(HUMAN), (rs, i) -> new VisitStatsResponse.PageReading(rs.getString("path"),
                rs.getLong("visitors"), Math.round(rs.getDouble("median_seconds")), rs.getLong("reached25"),
                rs.getLong("reached50"), rs.getLong("reached75"), rs.getLong("reached100")), from, to, TOP);
    }

    private List<VisitStatsResponse.Click> clicks(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT path, target, count(DISTINCT day::text || visitor) AS visitors, count(*) AS clicks
                FROM site_visit WHERE day BETWEEN ? AND ? AND kind = 'CLICK' AND %s
                GROUP BY path, target ORDER BY visitors DESC, clicks DESC, path, target LIMIT ?
                """.formatted(HUMAN), (rs, i) -> new VisitStatsResponse.Click(rs.getString("path"),
                rs.getString("target"), rs.getLong("visitors"), rs.getLong("clicks")), from, to, TOP_CLICKS);
    }

    /** Visitor-days per target of one named kind: the registration fields reached, or the errors met. */
    private List<VisitStatsResponse.Share> named(LocalDate from, LocalDate to, Kind kind) {
        return jdbc.query("""
                SELECT target, count(DISTINCT day::text || visitor) AS visitors
                FROM site_visit WHERE day BETWEEN ? AND ? AND kind = ? AND %s
                GROUP BY target ORDER BY visitors DESC, target LIMIT ?
                """.formatted(HUMAN), (rs, i) -> new VisitStatsResponse.Share(rs.getString("target"),
                rs.getLong("visitors")), from, to, kind.name(), TOP);
    }

    /** When visitors arrive: the Kyiv hour and weekday of each visitor-day's first row, every slot present. */
    private Map<String, List<VisitStatsResponse.Slot>> slots(LocalDate from, LocalDate to) {
        long[] hours = new long[24];
        long[] weekdays = new long[8];
        jdbc.query("""
                WITH firsts AS (
                    SELECT DISTINCT ON (day, visitor) occurred_at AT TIME ZONE 'Europe/Kyiv' AS local_time
                    FROM site_visit WHERE day BETWEEN ? AND ? AND %s
                    ORDER BY day, visitor, occurred_at, id)
                SELECT 'hour' AS dimension, extract(hour FROM local_time)::int AS slot, count(*) AS visitors
                FROM firsts GROUP BY 2
                UNION ALL
                SELECT 'weekday', extract(isodow FROM local_time)::int, count(*) FROM firsts GROUP BY 2
                """.formatted(HUMAN), rs -> {
            int slot = rs.getInt("slot");
            if ("hour".equals(rs.getString("dimension"))) {
                hours[slot] = rs.getLong("visitors");
            } else {
                weekdays[slot] = rs.getLong("visitors");
            }
        }, from, to);
        List<VisitStatsResponse.Slot> byHour = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            byHour.add(new VisitStatsResponse.Slot(h, hours[h]));
        }
        List<VisitStatsResponse.Slot> byWeekday = new ArrayList<>(7);
        for (int d = 1; d <= 7; d++) {
            byWeekday.add(new VisitStatsResponse.Slot(d, weekdays[d]));
        }
        return Map.of("hour", byHour, "weekday", byWeekday);
    }

    /** Visitors and registrations side by side, the busiest source first. */
    static List<VisitStatsResponse.Channel> channels(Map<String, Long> visitors, Map<String, Long> registered) {
        // HashMap, never Map.of(): «без UTM» is a null key, and an immutable map throws on looking one up.
        Map<String, Long> v = visitors == null ? new HashMap<>() : visitors;
        Map<String, Long> r = registered == null ? new HashMap<>() : registered;
        Set<String> keys = new HashSet<>(v.keySet());
        keys.addAll(r.keySet());
        return keys.stream()
                .map(k -> new VisitStatsResponse.Channel(k, v.getOrDefault(k, 0L), r.getOrDefault(k, 0L)))
                .sorted(Comparator.comparingLong(VisitStatsResponse.Channel::visitors).reversed()
                        .thenComparing(Comparator.comparingLong(VisitStatsResponse.Channel::registered).reversed())
                        .thenComparing(c -> c.source() == null ? "" : c.source()))
                .limit(TOP)
                .toList();
    }

    private static List<VisitStatsResponse.Share> shares(Map<String, Long> visitors) {
        if (visitors == null) {
            return List.of();
        }
        return visitors.entrySet().stream()
                .map(e -> new VisitStatsResponse.Share(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(VisitStatsResponse.Share::visitors).reversed()
                        .thenComparing(s -> s.value() == null ? "" : s.value()))
                .limit(TOP)
                .toList();
    }

    private static Kind kind(String raw) {
        try {
            return raw == null ? null : Kind.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Older browsers still name Kyiv by its retired spellings; one city must not split into three rows. */
    static String timeZone(String raw) {
        if (raw == null || !TIME_ZONE.matcher(raw.trim()).matches()) {
            return null;
        }
        String zone = raw.trim();
        return KYIV_ALIASES.contains(zone) ? "Europe/Kyiv" : zone;
    }

    /** A click or form-step label, lower-cased, or null when it is not one of ours. */
    static String target(String raw) {
        if (raw == null) {
            return null;
        }
        String label = raw.trim().toLowerCase(Locale.ROOT);
        return TARGET.matcher(label).matches() ? label : null;
    }

    private static Integer clamp(Integer value, int max) {
        return value == null ? null : Math.max(0, Math.min(value, max));
    }

    /** The language alone, lower-cased: «uk-UA» and «uk» are one audience. */
    static String language(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = LANGUAGE.matcher(raw.trim());
        return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long count(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
