package com.majstr.backend.integration;

import com.majstr.backend.dto.BroadcastSendRequest;
import com.majstr.backend.dto.BroadcastSummary;
import com.majstr.backend.entity.BroadcastRecipient;
import com.majstr.backend.entity.BroadcastSegment;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateKind;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Project;
import com.majstr.backend.repository.BroadcastRecipientRepository;
import com.majstr.backend.repository.BroadcastRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.broadcast.AudienceRules;
import com.majstr.backend.service.broadcast.BroadcastAudience;
import com.majstr.backend.service.broadcast.BroadcastService;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Broadcasts against real Postgres and the real security chain: the segment SQL (estimate lines
 * through projects, ADDENDUM excluded), who may never be in a segment, the send recording one row
 * per letter, and the unsubscribe link working anonymously.
 *
 * <p>The database is shared across the run, so assertions are about the masters made HERE, never
 * absolute segment sizes. {@code application-test.yml} pins the Resend key blank, so every letter
 * ends FAILED with «RESEND_API_KEY not set» — which is exactly what proves the row is written.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BroadcastIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired Environment env;
    @Autowired BroadcastAudience audience;
    @Autowired BroadcastService broadcastService;
    @Autowired BroadcastRepository broadcastRepository;
    @Autowired BroadcastRecipientRepository recipientRepository;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void everyMasterLandsInTheSegmentOfHisStep_fromTheRealQueries() {
        UUID unverified = master("FREE", "USER", false, 10, 10);
        UUID noObject = master("FREE", "USER", true, 10, 1);
        UUID noEstimate = master("FREE", "USER", true, 10, 1);
        object(noEstimate);
        UUID emptyEstimate = master("FREE", "USER", true, 10, 1);
        estimate(object(emptyEstimate), EstimateKind.REGULAR, 4);
        estimate(object(emptyEstimate), EstimateKind.REGULAR, 2);
        UUID inactive = master("FREE", "USER", true, 90, 40);
        estimate(object(inactive), EstimateKind.REGULAR, 7);
        UUID activeAndFull = master("FREE", "USER", true, 90, 1);
        estimate(object(activeAndFull), EstimateKind.REGULAR, 7);
        // An ADDENDUM is the act's paperwork, not an estimate he wrote: 9 lines there count for nothing.
        UUID onlyAddendum = master("FREE", "USER", true, 10, 1);
        estimate(object(onlyAddendum), EstimateKind.ADDENDUM, 9);

        Map<BroadcastSegment, Set<UUID>> got = idsBySegment();

        assertThat(got.get(BroadcastSegment.UNVERIFIED)).contains(unverified);
        assertThat(got.get(BroadcastSegment.NO_OBJECT)).contains(noObject);
        assertThat(got.get(BroadcastSegment.NO_ESTIMATE)).contains(noEstimate, onlyAddendum);
        assertThat(got.get(BroadcastSegment.EMPTY_ESTIMATE)).contains(emptyEstimate);
        assertThat(got.get(BroadcastSegment.INACTIVE)).contains(inactive);
        assertThat(allIds(got)).doesNotContain(activeAndFull);
    }

    @Test
    void adminsPayingMastersAndTheUnsubscribedAreInNoSegment() {
        UUID admin = master("FREE", "ADMIN", false, 30, 30);
        UUID pro = master("PRO", "USER", false, 30, 30);
        UUID team = master("TEAM", "USER", false, 30, 30);
        UUID optedOut = master("FREE", "USER", false, 30, 30);
        jdbc.update("UPDATE users SET broadcast_opt_out_at = now() WHERE id = ?", optedOut);

        assertThat(allIds(idsBySegment())).doesNotContain(admin, pro, team, optedOut);
    }

    @Test
    void aSendWritesOneRowPerTickedMaster_andTheLinkUnsubscribesAnonymously() throws Exception {
        UUID inactive = master("FREE", "USER", true, 90, 40);
        estimate(object(inactive), EstimateKind.REGULAR, 6);
        UUID notInSegment = master("FREE", "USER", true, 90, 1);

        BroadcastSummary summary = broadcastService.send(new BroadcastSendRequest(BroadcastSegment.INACTIVE,
                "Як Вам Майстр?", "Вітаю, {{імʼя}}!", List.of(inactive, notInSegment)), "admin@t.ua").orElseThrow();

        // The server intersects with the segment it computes NOW: the stray id gets nothing.
        assertThat(summary.total()).isEqualTo(1);
        BroadcastRecipient row = awaitFinished(summary.id());
        assertThat(row.getUserId()).isEqualTo(inactive);
        assertThat(row.getStatus()).isEqualTo(BroadcastRecipient.Status.FAILED);
        assertThat(row.getError()).isEqualTo("RESEND_API_KEY not set");
        assertThat(broadcastService.history()).anySatisfy(b -> {
            assertThat(b.id()).isEqualTo(summary.id());
            assertThat(b.failed()).isEqualTo(1);
            assertThat(b.finishedAt()).isNotNull();
        });

        String url = "/api/public/unsubscribe/" + row.getUnsubscribeToken();
        HttpResponse<String> page = http.send(get(url), HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("<form method=\"post\"");
        // Opening the link must not be the action — a mail scanner opens every link.
        assertThat(userRepository.findById(inactive).orElseThrow().getBroadcastOptOutAt()).isNull();

        HttpResponse<String> done = http.send(post(url), HttpResponse.BodyHandlers.ofString());
        assertThat(done.statusCode()).isEqualTo(200);
        assertThat(userRepository.findById(inactive).orElseThrow().getBroadcastOptOutAt()).isNotNull();
        assertThat(allIds(idsBySegment())).doesNotContain(inactive);

        assertThat(http.send(post("/api/public/unsubscribe/no-such-token"), HttpResponse.BodyHandlers.ofString())
                .statusCode()).isEqualTo(404);
    }

    @Test
    void theAdminEndpointsAreNotPublic() throws Exception {
        assertThat(http.send(get("/api/admin/broadcasts/segments"), HttpResponse.BodyHandlers.ofString())
                .statusCode()).isEqualTo(401);
    }

    // ---- fixture ----------------------------------------------------------

    private Map<BroadcastSegment, Set<UUID>> idsBySegment() {
        return audience.bySegment(Instant.now()).entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                e -> e.getValue().stream().map(AudienceRules.Master::id).collect(Collectors.toSet())));
    }

    private static Set<UUID> allIds(Map<BroadcastSegment, Set<UUID>> bySegment) {
        return bySegment.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
    }

    private BroadcastRecipient awaitFinished(UUID broadcastId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (broadcastRepository.findById(broadcastId).orElseThrow().getFinishedAt() != null) {
                break;
            }
            Thread.sleep(100);
        }
        List<BroadcastRecipient> rows = recipientRepository.findAll().stream()
                .filter(r -> r.getBroadcastId().equals(broadcastId)).toList();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private UUID master(String plan, String role, boolean verified, int registeredDaysAgo, int lastActiveDaysAgo) {
        UUID id = UUID.randomUUID();
        Timestamp registered = Timestamp.from(Instant.now().minus(Duration.ofDays(registeredDaysAgo)));
        Timestamp lastActive = Timestamp.from(Instant.now().minus(Duration.ofDays(lastActiveDaysAgo)));
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone, company_name,
                                   referral_code, email_verified, plan, role, created_at, updated_at, last_active_at)
                VALUES (?, ?, ?, 'x', 'Петро', '+380', 'ФОП', ?, ?, ?, ?, ?, ?, ?)
                """, id, id + "@t.ua", id + "@t.ua", id.toString().substring(0, 10), verified, plan, role,
                registered, registered, lastActive);
        return id;
    }

    private Project object(UUID ownerId) {
        return projectRepository.save(Project.builder()
                .owner(userRepository.getReferenceById(ownerId))
                .name("Обʼєкт")
                .address("вул. Тестова, 1")
                .status(com.majstr.backend.entity.ProjectStatus.DRAFT)
                .build());
    }

    private void estimate(Project project, EstimateKind kind, int lines) {
        Estimate e = estimateRepository.save(Estimate.builder()
                .project(project)
                .kind(kind)
                .status(kind == EstimateKind.ADDENDUM ? EstimateStatus.SIGNED : EstimateStatus.DRAFT)
                .build());
        for (int i = 0; i < lines; i++) {
            jdbc.update("""
                    INSERT INTO estimate_items (id, estimate_id, type, name, unit, quantity, unit_price, line_total,
                                                base_detached, sort_order, quantity_manual)
                    VALUES (?, ?, 'WORK', ?, 'M2', 1, 100, 100, FALSE, ?, FALSE)
                    """, UUID.randomUUID(), e.getId(), "Робота " + i, i);
        }
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(uri(path)).header("X-Forwarded-For", "198.51.100.7").GET().build();
    }

    private HttpRequest post(String path) {
        return HttpRequest.newBuilder(uri(path)).header("X-Forwarded-For", "198.51.100.7")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("List-Unsubscribe=One-Click")).build();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
    }
}
