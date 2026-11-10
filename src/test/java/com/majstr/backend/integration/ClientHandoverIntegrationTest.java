package com.majstr.backend.integration;

import com.majstr.backend.entity.BroadcastSegment;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.security.JwtService;
import com.majstr.backend.service.ClientHandoffs;
import com.majstr.backend.service.broadcast.AudienceRules;
import com.majstr.backend.service.broadcast.BroadcastAudience;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * iteration-first-experience, over a real socket: the client preview leaves no footprint, a PDF
 * leaves exactly one row (V196), and the two feed the same «Віддали клієнту» set the funnel and the
 * broadcast segment read.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientHandoverIntegrationTest extends IntegrationTestBase {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");

    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;
    @Autowired ClientHandoffs handoffs;
    @Autowired BroadcastAudience broadcastAudience;

    private final HttpClient http = HttpClient.newHttpClient();

    private User owner;
    private String token;
    private UUID projectId;
    private UUID estimateId;

    @BeforeEach
    void seed() throws Exception {
        String unique = UUID.randomUUID().toString();
        String email = unique + "@majstr.test";
        owner = userRepository.save(User.builder()
                .email(email).emailCanonical(email).passwordHash("x")
                .fullName("Майстер Превʼю").emailVerified(true)
                .plan(Plan.FREE).role(Role.USER)
                .referralCode(unique.substring(0, 10))
                .build());
        token = "Bearer " + jwtService.generateAccessToken(owner.getId(), owner.getEmail());
        projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO projects (id, owner_id, name, status) VALUES (?, ?, 'Кухня на Подолі', 'IN_PROGRESS')",
                projectId, owner.getId());
        HttpResponse<String> created = send("POST", "/api/projects/" + projectId + "/estimates", "{}", true);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        estimateId = UUID.fromString(match(ID, created.body()));
    }

    @Test
    void thePreviewShowsTheClientsPage_andLeavesNoFootprint() throws Exception {
        addLines(2);
        HttpResponse<String> issued = send("POST", "/api/estimates/" + estimateId + "/client-preview", null, true);
        assertThat(issued.statusCode()).as(issued.body()).isEqualTo(200);
        String url = match(Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\""), issued.body());
        assertThat(url).contains("/portal/index.html?v=");
        String pass = URLDecoder.decode(url.substring(url.indexOf("?v=") + 3), StandardCharsets.UTF_8);

        HttpResponse<String> page = send("GET", "/api/public/preview/" + pass, null, false);
        assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
        assertThat(page.body()).contains("Кухня на Подолі").contains("Шпаклівка 0");

        // Nothing minted, nothing marked: no link row of either kind, still a DRAFT, not «shared».
        assertThat(count("SELECT COUNT(*) FROM estimate_share_links WHERE estimate_id = ?", estimateId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM project_share_links WHERE project_id = ?", projectId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM estimates WHERE id = ?", String.class, estimateId))
                .isEqualTo("DRAFT");
        assertThat(handoffs.shared()).doesNotContainKey(owner.getId());

        // Read-only: no door to sign, ask or print exists under the pass.
        assertThat(send("POST", "/api/public/preview/" + pass + "/sign",
                "{\"signerName\":\"Клієнт\",\"version\":0}", false).statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(send("GET", "/api/public/preview/" + pass + "/pdf", null, false).statusCode())
                .isGreaterThanOrEqualTo(400);
        assertThat(count("SELECT COUNT(*) FROM pdf_render WHERE owner_id = ?", owner.getId())).isZero();
        // A pass the server never issued is a dead link.
        assertThat(send("GET", "/api/public/preview/" + pass.substring(0, pass.length() - 3) + "abc", null, false)
                .statusCode()).isEqualTo(404);
    }

    @Test
    void theObjectPortalPreviewShowsTheTickedSet_andPublishesNothing() throws Exception {
        addLines(1);
        HttpResponse<String> other = send("POST", "/api/projects/" + projectId + "/estimates",
                "{\"name\":\"Не позначений\"}", true);
        assertThat(other.statusCode()).isEqualTo(201);

        HttpResponse<String> issued = send("POST", "/api/projects/" + projectId + "/portal/preview",
                "{\"estimateIds\":[\"" + estimateId + "\"]}", true);
        assertThat(issued.statusCode()).as(issued.body()).isEqualTo(200);
        String url = match(Pattern.compile("\"url\"\s*:\s*\"([^\"]+)\""), issued.body());
        assertThat(url).contains("/portal/index.html?vp=");
        String pass = URLDecoder.decode(url.substring(url.indexOf("?vp=") + 4), StandardCharsets.UTF_8);

        HttpResponse<String> page = send("GET", "/api/public/preview-portal/" + pass, null, false);
        assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
        assertThat(page.body()).contains("Кухня на Подолі").contains("Шпаклівка 0").doesNotContain("Не позначений");

        // Nothing published, minted or marked: the client's real portal is untouched.
        assertThat(count("SELECT COUNT(*) FROM project_share_links WHERE project_id = ?", projectId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM estimates WHERE project_id = ? AND portal_visible", projectId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM estimates WHERE id = ?", String.class, estimateId))
                .isEqualTo("DRAFT");
        assertThat(send("POST", "/api/public/preview-portal/" + pass + "/estimates/" + estimateId + "/sign",
                "{\"signerName\":\"Клієнт\",\"version\":0}", false).statusCode()).isGreaterThanOrEqualTo(400);
        // An estimate from somewhere else cannot be asked for.
        assertThat(send("POST", "/api/projects/" + projectId + "/portal/preview",
                "{\"estimateIds\":[\"" + UUID.randomUUID() + "\"]}", true).statusCode()).isEqualTo(404);
        // The single-estimate pass never opens the portal view, nor the other way round.
        String single = URLDecoder.decode(match(Pattern.compile("\\?v=([^\"]+)"),
                send("POST", "/api/estimates/" + estimateId + "/client-preview", null, true).body()), StandardCharsets.UTF_8);
        assertThat(send("GET", "/api/public/preview-portal/" + single, null, false).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/public/preview/" + pass, null, false).statusCode()).isEqualTo(404);
    }

    @Test
    void theMastersPdfIsOneRow_andMakesHimHandedToClient() throws Exception {
        assertThat(handoffs.handedToClient()).doesNotContainKey(owner.getId());

        HttpResponse<String> pdf = send("GET", "/api/estimates/" + estimateId + "/pdf", null, true);
        assertThat(pdf.statusCode()).isEqualTo(200);

        awaitRows("MASTER", 1);
        assertThat(handoffs.pdfMade()).containsKey(owner.getId());
        assertThat(handoffs.handedToClient()).containsKey(owner.getId());
        assertThat(handoffs.shared()).doesNotContainKey(owner.getId());
        assertThat(jdbc.queryForObject("SELECT project_id FROM pdf_render WHERE owner_id = ?", UUID.class,
                owner.getId())).isEqualTo(projectId);
    }

    @Test
    void theClientsDownloadIsCountedApart_andNeverAsTheMasters() throws Exception {
        HttpResponse<String> link = send("POST", "/api/estimates/" + estimateId + "/share", null, true);
        assertThat(link.statusCode()).as(link.body()).isEqualTo(201);
        String shareToken = match(Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\""), link.body());

        assertThat(send("GET", "/api/public/estimates/" + shareToken + "/pdf", null, false).statusCode())
                .isEqualTo(200);

        awaitRows("CLIENT", 1);
        assertThat(handoffs.clientDownloadedPdf()).containsKey(owner.getId());
        assertThat(handoffs.pdfMade()).doesNotContainKey(owner.getId());
    }

    @Test
    void aPdfTakesHimOutOfTheNotHandedOverSegment() throws Exception {
        addLines(AudienceRules.FULL_ESTIMATE_LINES);
        // Settled: registered long enough ago to be written to at all.
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(10))), owner.getId());
        assertThat(ids(broadcastAudience.members(BroadcastSegment.NOT_HANDED_OVER, Instant.now())))
                .contains(owner.getId());

        assertThat(send("GET", "/api/estimates/" + estimateId + "/pdf", null, true).statusCode()).isEqualTo(200);
        awaitRows("MASTER", 1);

        for (BroadcastSegment s : List.of(BroadcastSegment.NOT_HANDED_OVER, BroadcastSegment.EMPTY_ESTIMATE)) {
            assertThat(ids(broadcastAudience.members(s, Instant.now()))).doesNotContain(owner.getId());
        }
    }

    private void addLines(int n) throws Exception {
        for (int i = 0; i < n; i++) {
            HttpResponse<String> res = send("POST", "/api/estimates/" + estimateId + "/items", """
                    {"type":"WORK","name":"Шпаклівка %d","unit":"M2","quantity":10,"unitPrice":100}
                    """.formatted(i), true);
            assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        }
    }

    /** The row is written on its own pool after the response — wait for it, five seconds at most. */
    private void awaitRows(String reader, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        String sql = "SELECT COUNT(*) FROM pdf_render WHERE owner_id = ? AND reader = ?";
        while (System.currentTimeMillis() < deadline
                && jdbc.queryForObject(sql, Long.class, owner.getId(), reader) < expected) {
            Thread.sleep(50);
        }
        assertThat(jdbc.queryForObject(sql, Long.class, owner.getId(), reader)).isEqualTo(expected);
    }

    private long count(String sql, UUID id) {
        return jdbc.queryForObject(sql, Long.class, id);
    }

    private static List<UUID> ids(List<AudienceRules.Master> masters) {
        return masters.stream().map(AudienceRules.Master::id).toList();
    }

    private static String match(Pattern p, String body) {
        Matcher m = p.matcher(body);
        assertThat(m.find()).as(body).isTrue();
        return m.group(1);
    }

    private HttpResponse<String> send(String method, String path, String body, boolean authed) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(
                "http://localhost:" + env.getProperty("local.server.port") + path));
        if (authed) {
            b.header("Authorization", token);
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
