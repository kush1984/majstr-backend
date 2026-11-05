package com.majstr.backend.integration;

import com.majstr.backend.dto.VisitStatsResponse;
import com.majstr.backend.service.SiteVisitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The visit beacon end to end: what a row keeps, what it never keeps, who is not a visitor, and the
 * admin report's one rule — each visitor-day counted once, by its first row.
 *
 * <p>The schema is shared with every other integration test, so each test works on its own made-up
 * path or referrer host and reads only the rows that carry it.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SiteVisitIntegrationTest extends IntegrationTestBase {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);
    private static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1";
    private static final String ANDROID = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";

    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    @Autowired SiteVisitService visits;

    private final HttpClient http = HttpClient.newHttpClient();
    private String tag;

    @BeforeEach
    void tag() {
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void aRowKeepsThePageAndItsSource_andNoAddressAnywhere() throws Exception {
        String ip = nextIp();
        HttpResponse<String> res = post(ip, IPHONE, """
                {"kind":"PAGEVIEW","path":"/it-%s?token=secret#x","referrerHost":"www.Google.com",
                 "entryPath":"/kalkulyator-plytky?utm_source=x","utmSource":" tiktok ","utmCampaign":"spring",
                 "timeZone":"Europe/Warsaw","language":"uk-UA"}
                """.formatted(tag));

        assertThat(res.statusCode()).isEqualTo(204);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM site_visit WHERE path = ?", "/it-" + tag);
        assertThat(row).containsEntry("kind", "PAGEVIEW")
                .containsEntry("referrer_host", "google.com")
                .containsEntry("entry_path", "/kalkulyator-plytky")
                .containsEntry("utm_source", "tiktok")
                .containsEntry("utm_campaign", "spring")
                .containsEntry("device", "mobile")
                .containsEntry("os", "iOS")
                .containsEntry("browser", "Safari")
                .containsEntry("time_zone", "Europe/Warsaw")
                .containsEntry("language", "uk")
                .containsEntry("day", java.sql.Date.valueOf(LocalDate.now(KYIV)));
        assertThat(row.values()).noneMatch(v -> v != null && v.toString().contains(ip));
        assertThat(row.values()).noneMatch(v -> v != null && v.toString().contains("iPhone OS"));
    }

    @Test
    void oneBrowserIsOneVisitorADay_anotherBrowserIsAnother() throws Exception {
        String ip = nextIp();
        post(ip, IPHONE, page("/it-" + tag));
        post(ip, IPHONE, page("/it-" + tag));
        post(ip, ANDROID, page("/it-" + tag));

        List<String> visitors = jdbc.queryForList(
                "SELECT visitor FROM site_visit WHERE path = ? ORDER BY id", String.class, "/it-" + tag);
        assertThat(visitors).hasSize(3);
        assertThat(visitors.get(0)).isEqualTo(visitors.get(1)).isNotEqualTo(visitors.get(2)).hasSize(16);
    }

    @Test
    void botsUnknownKindsAndBrokenPathsAreDroppedWithoutSayingSo() throws Exception {
        String ip = nextIp();
        assertThat(post(ip, "Mozilla/5.0 (compatible; Googlebot/2.1)", page("/it-" + tag)).statusCode()).isEqualTo(204);
        assertThat(post(ip, null, page("/it-" + tag)).statusCode()).isEqualTo(204);
        assertThat(post(ip, IPHONE, """
                {"kind":"CLICKED_SOMETHING","path":"/it-%s"}""".formatted(tag)).statusCode()).isEqualTo(204);
        assertThat(post(ip, IPHONE, """
                {"kind":"PAGEVIEW","path":"not-a-path-%s"}""".formatted(tag)).statusCode()).isEqualTo(204);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM site_visit WHERE path LIKE ? OR path LIKE ?",
                Long.class, "%it-" + tag, "%" + tag)).isZero();
    }

    @Test
    void aBodyWithNoPageIsABadRequest() throws Exception {
        assertThat(post(nextIp(), IPHONE, "{\"kind\":\"PAGEVIEW\"}").statusCode()).isEqualTo(400);
    }

    @Test
    void oneAddressCannotFillTheTable() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 70; i++) {
            assertThat(post(ip, IPHONE, page("/it-" + tag)).statusCode()).isEqualTo(204);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM site_visit WHERE path = ?", Long.class, "/it-" + tag))
                .isEqualTo(60L);
    }

    @Test
    void theReportTakesEachVisitorDayOnceByItsFirstRow_besideTheRegistrationsOfTheSameSource() throws Exception {
        String host = "it-" + tag + ".example";
        String later = "later-" + tag + ".example";
        String first = nextIp();
        post(first, IPHONE, sourced("/", host));
        post(first, IPHONE, sourced("/register", later));
        post(first, IPHONE, """
                {"kind":"REGISTER_STARTED","path":"/register","referrerHost":"%s"}""".formatted(host));
        post(nextIp(), IPHONE, sourced("/", host));
        post(nextIp(), ANDROID, sourced("/", host));
        registeredMaster(host);

        VisitStatsResponse stats = visits.stats(1);

        assertThat(stats.referrers()).contains(new VisitStatsResponse.Channel(host, 3, 1));
        assertThat(stats.referrers()).extracting(VisitStatsResponse.Channel::source).doesNotContain(later);
        assertThat(stats.from()).isEqualTo(stats.to()).isEqualTo(LocalDate.now(KYIV));
        assertThat(stats.days()).singleElement().satisfies(d -> {
            assertThat(d.visitors()).isGreaterThanOrEqualTo(3);
            assertThat(d.registered()).isGreaterThanOrEqualTo(1);
        });
        assertThat(stats.sawRegister()).isGreaterThanOrEqualTo(1);
        assertThat(stats.startedRegister()).isGreaterThanOrEqualTo(1);
        assertThat(stats.devices()).extracting(VisitStatsResponse.Share::value).contains("mobile");
    }

    @Test
    void aPageIsCountedInViewsAndInVisitors() throws Exception {
        String ip = nextIp();
        post(ip, IPHONE, page("/it-" + tag));
        post(ip, IPHONE, page("/it-" + tag));
        post(nextIp(), IPHONE, page("/it-" + tag));

        assertThat(visits.stats(1).pages()).contains(new VisitStatsResponse.Page("/it-" + tag, 3, 2));
    }

    @Test
    void yesterdaysSaltAndOldRowsGo_todaysSaltStays() throws Exception {
        LocalDate today = LocalDate.now(KYIV);
        post(nextIp(), IPHONE, page("/it-" + tag));
        jdbc.update("INSERT INTO site_visit_salt (day, salt) VALUES (?, '\\x00') ON CONFLICT (day) DO NOTHING",
                today.minusDays(1));
        jdbc.update("""
                INSERT INTO site_visit (day, kind, visitor, path, device, os, browser)
                VALUES (?, 'PAGEVIEW', 'old', ?, 'mobile', 'iOS', 'Safari')
                """, today.minusDays(401), "/old-" + tag);

        visits.purge();

        assertThat(jdbc.queryForList("SELECT day FROM site_visit_salt", LocalDate.class)).containsExactly(today);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM site_visit WHERE path = ?", Long.class, "/old-" + tag))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM site_visit WHERE path = ?", Long.class, "/it-" + tag))
                .isOne();
    }

    private void registeredMaster(String referrerHost) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone, company_name,
                                   referral_code, email_verified, plan, role, created_at, updated_at,
                                   first_referrer_host)
                VALUES (?, ?, ?, 'x', 'Петро', '+380', 'ФОП', ?, false, 'FREE', 'USER', ?, ?, ?)
                """, id, id + "@t.ua", id + "@t.ua", id.toString().substring(0, 10), now, now, referrerHost);
    }

    private static String page(String path) {
        return "{\"kind\":\"PAGEVIEW\",\"path\":\"" + path + "\"}";
    }

    private static String sourced(String path, String referrerHost) {
        return "{\"kind\":\"PAGEVIEW\",\"path\":\"" + path + "\",\"referrerHost\":\"" + referrerHost + "\"}";
    }

    private static String nextIp() {
        return "198.51.100." + NEXT_IP.getAndIncrement();
    }

    private HttpResponse<String> post(String ip, String userAgent, String json) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + env.getProperty("local.server.port") + "/api/public/visit"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", ip)
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (userAgent != null) {
            req.header("User-Agent", userAgent);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }
}
