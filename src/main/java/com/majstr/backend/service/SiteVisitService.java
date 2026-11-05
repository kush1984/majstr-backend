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
    private static final SecureRandom RANDOM = new SecureRandom();

    enum Kind { PAGEVIEW, REGISTER_STARTED }

    private record DaySalt(LocalDate day, byte[] salt) {}

    private final JdbcTemplate jdbc;
    private final AtomicReference<DaySalt> salt = new AtomicReference<>();

    /** Drops quietly whatever is not a person's page view: a bot, an unknown kind, a malformed path. */
    public void record(VisitRequest req, String ip, String userAgent) {
        VisitorAgent agent = VisitorAgent.of(userAgent);
        Kind kind = kind(req.kind());
        String path = FirstTouch.path(req.path());
        if (agent == null || kind == null || path == null) {
            return;
        }
        LocalDate day = LocalDate.now(LocalizationConfig.ZONE);
        jdbc.update("""
                INSERT INTO site_visit (day, kind, visitor, path, referrer_host, entry_path, utm_source, utm_medium,
                                        utm_campaign, device, os, browser, time_zone, language)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                day, kind.name(), visitor(day, ip, userAgent), path,
                FirstTouch.host(req.referrerHost()), FirstTouch.path(req.entryPath()),
                trimToNull(req.utmSource()), trimToNull(req.utmMedium()), trimToNull(req.utmCampaign()),
                agent.device(), agent.os(), agent.browser(),
                timeZone(req.timeZone()), language(req.language()));
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
                SELECT count(*) FILTER (WHERE kind = 'PAGEVIEW') AS pageviews,
                       count(DISTINCT day::text || visitor) AS visitors,
                       count(DISTINCT day::text || visitor)
                           FILTER (WHERE kind = 'PAGEVIEW' AND path = '/register') AS saw_register,
                       count(DISTINCT day::text || visitor) FILTER (WHERE kind = 'REGISTER_STARTED') AS started
                FROM site_visit WHERE day BETWEEN ? AND ?
                """, from, to);
        long registered = count(jdbc.queryForObject("""
                SELECT count(*) FROM users WHERE role = 'USER' AND created_at >= ? AND created_at < ?
                """, Long.class, regFrom, regTo));

        Map<String, Map<String, Long>> visitorsBy = firstTouchShares(from, to);
        Map<String, Map<String, Long>> registeredBy = registrationsByFirstTouch(regFrom, regTo);

        return new VisitStatsResponse(from, to, registeredFrom,
                count(totals.get("pageviews")), count(totals.get("visitors")),
                count(totals.get("saw_register")), count(totals.get("started")), registered,
                days(from, to, regFrom, regTo),
                channels(visitorsBy.get("referrer"), registeredBy.get("referrer")),
                channels(visitorsBy.get("utm"), registeredBy.get("utm")),
                channels(visitorsBy.get("entry"), registeredBy.get("entry")),
                pages(from, to),
                shares(visitorsBy.get("device")), shares(visitorsBy.get("os")), shares(visitorsBy.get("browser")),
                shares(visitorsBy.get("zone")), shares(visitorsBy.get("language")));
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
                    FROM site_visit WHERE day BETWEEN ? AND ?
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
                """, rs -> {
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
                FROM site_visit WHERE day BETWEEN ? AND ? GROUP BY day
                """, rs -> {
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
                FROM site_visit WHERE day BETWEEN ? AND ? AND kind = 'PAGEVIEW'
                GROUP BY path ORDER BY visitors DESC, pageviews DESC, path LIMIT ?
                """, (rs, i) -> new VisitStatsResponse.Page(rs.getString("path"), rs.getLong("pageviews"),
                rs.getLong("visitors")), from, to, TOP);
    }

    /** Visitors and registrations side by side, the busiest source first. */
    static List<VisitStatsResponse.Channel> channels(Map<String, Long> visitors, Map<String, Long> registered) {
        Map<String, Long> v = visitors == null ? Map.of() : visitors;
        Map<String, Long> r = registered == null ? Map.of() : registered;
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
