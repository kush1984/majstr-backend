package com.majstr.backend.integration;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.security.JwtService;
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
 * Receiving money, over a real socket, with the body the PWA actually sends.
 *
 * <p><b>Why this exists.</b> «Отримано» was unsaveable in production from V135 until the Jackson
 * default was corrected, and the whole suite stayed green throughout. There was no controller test
 * for payments at ALL, and every other test builds {@code PaymentReceiptRequest} in Java, so the
 * one step that was broken — JSON into a record — was never executed by anything. A standalone
 * MockMvc test could not have caught it either: it builds its own message converter, so it answers
 * about Jackson's defaults rather than about this application's.</p>
 *
 * <p>So the payload here is copied from {@code majstr-pwa/src/api/payments.ts} and its
 * {@code PaymentReceiptRequest} interface, as JSON TEXT rather than as a serialized DTO — writing
 * it as an object would re-introduce the exact blind spot, because the compiler would fill in
 * whatever the record happens to declare today.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentReceiptHttpIntegrationTest extends IntegrationTestBase {

    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;

    private final HttpClient http = HttpClient.newHttpClient();

    private String token;
    private UUID projectId;

    @BeforeEach
    void seed() {
        String unique = UUID.randomUUID().toString();
        String email = unique + "@majstr.test";
        User owner = userRepository.save(User.builder()
                .email(email).emailCanonical(email).passwordHash("x")
                .fullName("Майстер").phone("+380000000000").companyName("ФОП")
                .plan(Plan.FREE).role(Role.USER)
                .referralCode(unique.substring(0, 10))
                .build());
        token = "Bearer " + jwtService.generateAccessToken(owner.getId(), owner.getEmail());

        projectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?, ?, 'Квартира на Лесі', 'вул. Тестова 1', 'IN_PROGRESS')
                """, projectId, owner.getId());
    }

    /**
     * The advance the master types on the object's economy screen. The body carries no
     * {@code materialRefund} — that sheet never asks about one — which is precisely the payload
     * that answered 400 in production for weeks.
     */
    @Test
    void theEconomySheetsAdvanceIsAccepted() throws Exception {
        HttpResponse<String> res = post("""
                {"planPaymentId":null,"label":"Завдаток","amount":5000,"receivedAt":"2026-09-19","resolution":null}
                """);

        assertThat(res.statusCode())
                .as("POST receipts answered %s: %s", res.statusCode(), res.body())
                .isEqualTo(201);
        assertThat(storedRefundFlag()).as("an omitted flag takes its default, it is not an error").isFalse();
    }

    /**
     * The edit sheet's body, which carries no flag either — and must therefore not move one. The
     * object screen has no switch for a material refund; «Мої гроші» owns it, and editing an
     * amount here used to be able to clear it and quietly change what the master «Заробив».
     */
    @Test
    void editingAnAmountLeavesAMaterialRefundFlagAlone() throws Exception {
        assertThat(post("""
                {"planPaymentId":null,"label":"Завдаток","amount":5000,"receivedAt":"2026-09-19","resolution":null}
                """).statusCode()).isEqualTo(201);
        jdbc.update("UPDATE payment_receipt SET material_refund = TRUE WHERE project_id = ?", projectId);

        HttpResponse<String> res = patch(receiptId(), """
                {"amount":6000,"receivedAt":"2026-09-19","label":"Завдаток"}
                """);

        assertThat(res.statusCode())
                .as("PATCH receipt answered %s: %s", res.statusCode(), res.body())
                .isEqualTo(200);
        assertThat(storedRefundFlag()).as("a sheet that cannot see the flag may not clear it").isTrue();
    }

    /** A field-level validation error stays a legible 400 — the fix must not have made the DTO lax. */
    @Test
    void anAmountThatIsTrulyMissingIsStillRefused() throws Exception {
        HttpResponse<String> res = post("""
                {"planPaymentId":null,"label":"Завдаток","receivedAt":"2026-09-19","resolution":null}
                """);

        assertThat(res.statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> post(String json) throws Exception {
        return send(HttpRequest.newBuilder()
                .uri(uri("/api/projects/" + projectId + "/payments/receipts"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    private HttpResponse<String> patch(UUID receiptId, String json) throws Exception {
        return send(HttpRequest.newBuilder()
                .uri(uri("/api/projects/" + projectId + "/payments/receipts/" + receiptId))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json)));
    }

    private HttpResponse<String> send(HttpRequest.Builder req) throws Exception {
        return http.send(req.header("Authorization", token).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
    }

    private UUID receiptId() {
        return jdbc.queryForObject(
                "SELECT id FROM payment_receipt WHERE project_id = ?", UUID.class, projectId);
    }

    private boolean storedRefundFlag() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT material_refund FROM payment_receipt WHERE project_id = ?", Boolean.class, projectId));
    }
}
