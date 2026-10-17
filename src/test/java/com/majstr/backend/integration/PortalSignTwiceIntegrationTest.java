package com.majstr.backend.integration;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A double tap on «Підписати» in the portal: the second request is a 409 the page treats as the
 * success it is — so it must carry the ESTIMATE_SIGNED code, a sentence meant for the CLIENT (not
 * the master's «Створіть новий кошторис»), and Ukrainian even from an English-language phone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PortalSignTwiceIntegrationTest extends IntegrationTestBase {

    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private String token;

    @BeforeEach
    void seed() {
        UUID owner = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, email_verified)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, TRUE)
                """, owner, owner + "@t.ua", owner + "@t.ua", owner.toString().substring(0, 8));
        UUID project = UUID.randomUUID();
        jdbc.update("INSERT INTO projects (id, owner_id, name, address, status) VALUES (?, ?, 'Квартира', 'вул. 1', 'IN_PROGRESS')",
                project, owner);
        UUID estimate = UUID.randomUUID();
        jdbc.update("INSERT INTO estimates (id, project_id, name, status) VALUES (?, ?, 'Кошторис', 'SENT')", estimate, project);
        jdbc.update("""
                INSERT INTO estimate_items (id, estimate_id, type, name, unit, quantity, unit_price, line_total, sort_order)
                VALUES (?, ?, 'WORK', 'Робота', 'PIECE', 1, 100, 100, 0)
                """, UUID.randomUUID(), estimate);
        token = "tok-" + UUID.randomUUID();
        jdbc.update("INSERT INTO estimate_share_links (id, estimate_id, token) VALUES (?, ?, ?)",
                UUID.randomUUID(), estimate, token);
    }

    @Test
    void theSecondSignatureIsA409ForTheClient_inUkrainian() throws Exception {
        long version = jdbc.queryForObject("""
                SELECT e.version FROM estimates e JOIN estimate_share_links l ON l.estimate_id = e.id WHERE l.token = ?
                """, Long.class, token);
        String body = "{\"clientName\":\"Клієнт\",\"clientPhone\":\"\",\"version\":" + version + "}";

        assertThat(sign(body).statusCode()).isEqualTo(200);
        HttpResponse<String> again = sign(body);

        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("\"code\":\"ESTIMATE_SIGNED\"")
                .contains("Цей кошторис уже підписано")
                .doesNotContain("Створіть новий кошторис");
    }

    private HttpResponse<String> sign(String json) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + env.getProperty("local.server.port")
                        + "/api/public/estimates/" + token + "/sign"))
                .header("Content-Type", "application/json")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("X-Forwarded-For", "203.0.113." + (int) (Math.random() * 200 + 1))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
