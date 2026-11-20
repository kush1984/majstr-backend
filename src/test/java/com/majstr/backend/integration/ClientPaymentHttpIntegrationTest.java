package com.majstr.backend.integration;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.AiExtractionException;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.security.JwtService;
import com.majstr.backend.service.PaymentNoticeService;
import com.majstr.backend.service.PaymentReceiptReader;
import com.majstr.backend.service.QrPng;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V217 — the client pays the master from the portal and tells him so, over a real socket with the
 * bodies the PWA and the portal page send.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientPaymentHttpIntegrationTest extends IntegrationTestBase {

    private static final String CARD = "4111111111111111";

    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;
    @Autowired PaymentNoticeService paymentNoticeService;

    /**
     * Swapped into the service for each test rather than a {@code @MockitoBean}: that would fork a
     * Spring context of its own, with its own connection pool, and the suite's one Postgres runs out
     * of connections («too many clients») once a few such contexts are cached.
     */
    private final PaymentReceiptReader receiptReader = mock(PaymentReceiptReader.class);
    private Object realReader;

    private final HttpClient http = HttpClient.newHttpClient();

    private UUID ownerId;
    private String bearer;
    private UUID projectId;
    private String portalToken;
    private String messageToken;

    @BeforeEach
    void swapReader() {
        Object target = AopTestUtils.getTargetObject(paymentNoticeService);
        realReader = ReflectionTestUtils.getField(target, "receiptReader");
        ReflectionTestUtils.setField(target, "receiptReader", receiptReader);
    }

    @AfterEach
    void restoreReader() {
        Object target = AopTestUtils.getTargetObject(paymentNoticeService);
        ReflectionTestUtils.setField(target, "receiptReader", realReader);
    }

    @BeforeEach
    void seed() {
        String unique = UUID.randomUUID().toString();
        String email = unique + "@majstr.test";
        User owner = userRepository.save(User.builder()
                .email(email).emailCanonical(email).passwordHash("x")
                .fullName("Іван Петренко").phone("+380000000000").companyName("ФОП")
                .plan(Plan.FREE).role(Role.USER)
                .referralCode(unique.substring(0, 10))
                .build());
        ownerId = owner.getId();
        bearer = "Bearer " + jwtService.generateAccessToken(owner.getId(), owner.getEmail());
        projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO projects (id, owner_id, name, address, status) VALUES (?, ?, 'Квартира', 'вул. 1', 'IN_PROGRESS')",
                projectId, ownerId);
        portalToken = "p-" + UUID.randomUUID();
        messageToken = "m-" + UUID.randomUUID();
        jdbc.update("INSERT INTO project_share_links (id, project_id, token, kind) VALUES (?, ?, ?, 'PORTAL')",
                UUID.randomUUID(), projectId, portalToken);
        jdbc.update("INSERT INTO project_share_links (id, project_id, token, kind) VALUES (?, ?, ?, 'MESSAGE')",
                UUID.randomUUID(), projectId, messageToken);
    }

    // ---- «Налаштування» ------------------------------------------------------------------------

    @Test
    void settingsSaveRequisitesAndPayout_andRefuseABadCard() throws Exception {
        assertThat(putRequisites("""
                {"legalName":"ФОП Петренко Іван","taxId":"1234567890","iban":"UA213223130000026007233566001",
                 "payoutCard":"4111 1111 1111 1111","payoutLink":"https://www.liqpay.ua/pay/x"}
                """).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT payout_card FROM users WHERE id = ?", String.class, ownerId))
                .isEqualTo(CARD);

        HttpResponse<String> bad = putRequisites("""
                {"payoutCard":"4111 1111 1111 1112"}
                """);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.body()).contains("Номер картки");
    }

    /** An installed app from before still sends the requisites with the profile: ignored, never a 400 — and never cleared. */
    @Test
    void theOldProfileBodyWithRequisitesIsAccepted_andLeavesThemAlone() throws Exception {
        jdbc.update("UPDATE users SET iban = 'UA213223130000026007233566001' WHERE id = ?", ownerId);

        HttpResponse<String> res = send(HttpRequest.newBuilder()
                .uri(uri("/api/profile"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"fullName":"Іван Петренко","phone":"+380000000000","companyName":"ФОП","trades":["ELECTRICAL"],
                         "email":null,"legalName":"","taxId":"","iban":"","bankName":"","vatPayer":false}
                        """)), true);

        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT iban FROM users WHERE id = ?", String.class, ownerId))
                .isEqualTo("UA213223130000026007233566001");
    }

    // ---- the portal ----------------------------------------------------------------------------

    @Test
    void thePortalOffersNoPaymentUntilTheMasterFillsOne() throws Exception {
        assertThat(get("/api/public/portal/" + portalToken).body()).doesNotContain("payTo");

        jdbc.update("UPDATE users SET payout_card = ? WHERE id = ?", CARD, ownerId);

        assertThat(get("/api/public/portal/" + portalToken).body()).contains("\"card\":\"" + CARD + "\"");
    }

    @Test
    void theQrIsAPng_onlyWithIbanAndCode() throws Exception {
        jdbc.update("UPDATE users SET iban = 'UA213223130000026007233566001' WHERE id = ?", ownerId);
        assertThat(getBytes("/api/public/payment-notice/" + portalToken + "/qr.png").statusCode())
                .as("no РНОКПП, no QR").isEqualTo(404);

        jdbc.update("UPDATE users SET tax_id = '1234567890' WHERE id = ?", ownerId);
        HttpResponse<byte[]> png = getBytes("/api/public/payment-notice/" + portalToken + "/qr.png");
        assertThat(png.statusCode()).isEqualTo(200);
        assertThat(png.headers().firstValue("Content-Type")).hasValue("image/png");
    }

    // ---- «Я оплатив» ---------------------------------------------------------------------------

    /** The owner's ruling: a matching receipt puts the money in the object, no tap — notifications may be off. */
    @Test
    void aMatchingReceiptRecordsThePayment_andLinksTheNoticeToIt() throws Exception {
        jdbc.update("UPDATE users SET payout_card = ? WHERE id = ?", CARD, ownerId);
        when(receiptReader.read(eq("image/png"), any())).thenReturn(new PaymentReceiptReader.Read(
                new BigDecimal("10000.00"), "UAH", PaymentReceiptReader.Status.SUCCESS, today(), null, "1111", "Іван П."));

        HttpResponse<String> res = notice(portalToken, """
                {"amount":10000,"comment":"Завдаток"}
                """, QrPng.of("receipt"));

        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        Map<String, Object> row = noticeRow();
        assertThat(row.get("kind")).isEqualTo("PAYMENT");
        assertThat((BigDecimal) row.get("payment_amount")).isEqualByComparingTo("10000");
        assertThat(row.get("message")).isEqualTo("Завдаток");
        assertThat(row.get("payment_status")).isEqualTo("RECORDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_message_files WHERE message_id = ?",
                Integer.class, row.get("id"))).isOne();
        Map<String, Object> money = jdbc.queryForMap(
                "SELECT id, amount, label FROM payment_receipt WHERE project_id = ?", projectId);
        assertThat((BigDecimal) money.get("amount")).isEqualByComparingTo("10000");
        assertThat(money.get("label")).isEqualTo("Оплата з порталу · квитанція звірена");
        assertThat(row.get("payment_receipt_id")).isEqualTo(money.get("id"));

        // The master deletes the payment (a forged receipt): the notice stays, the link clears.
        jdbc.update("DELETE FROM payment_receipt WHERE id = ?", money.get("id"));
        assertThat(get("/api/projects/" + projectId + "/messages", true).body())
                .contains("\"paymentStatus\":\"RECORDED\"").contains("\"paymentRecorded\":false");
    }

    @Test
    void aReceiptThatDoesNotMatchWaitsForTheMaster_whoConfirmsItOnce() throws Exception {
        jdbc.update("UPDATE users SET payout_card = ? WHERE id = ?", CARD, ownerId);
        when(receiptReader.read(any(), any())).thenReturn(new PaymentReceiptReader.Read(
                new BigDecimal("9000"), "UAH", PaymentReceiptReader.Status.SUCCESS, today(), null, "1111", null));

        assertThat(notice(portalToken, "{\"amount\":10000}", QrPng.of("receipt")).statusCode()).isEqualTo(200);

        Map<String, Object> row = noticeRow();
        assertThat(row.get("payment_status")).isEqualTo("PENDING");
        assertThat(row.get("payment_note")).isEqualTo("У квитанції 9 000 ₴, а клієнт вказав 10 000 ₴.");
        assertThat(moneyRows()).isZero();

        String confirm = "/api/projects/" + projectId + "/messages/" + row.get("id") + "/payment/confirm";
        HttpResponse<String> first = post(confirm);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(first.body()).contains("\"paymentStatus\":\"RECORDED\"").contains("\"paymentRecorded\":true");
        assertThat(post(confirm).statusCode()).isEqualTo(200);
        assertThat(moneyRows()).as("a double tap records once").isOne();
        assertThat(jdbc.queryForObject("SELECT amount FROM payment_receipt WHERE project_id = ?",
                BigDecimal.class, projectId)).isEqualByComparingTo("10000");
    }

    @Test
    void noReceipt_oneThatCannotBeRead_waitsWithTheReason_andNotArrivedWritesNothing() throws Exception {
        jdbc.update("UPDATE users SET payout_card = ? WHERE id = ?", CARD, ownerId);
        assertThat(notice(portalToken, "{\"amount\":500}", null).statusCode()).isEqualTo(200);
        assertThat(noticeRow().get("payment_note")).isEqualTo("Клієнт не додав квитанцію.");

        jdbc.update("DELETE FROM project_messages WHERE project_id = ?", projectId);
        when(receiptReader.read(any(), any())).thenThrow(new AiExtractionException("error.ai.unavailable"));
        assertThat(notice(portalToken, "{\"amount\":500}", QrPng.of("receipt")).statusCode()).isEqualTo(200);
        Map<String, Object> row = noticeRow();
        assertThat(row.get("payment_status")).isEqualTo("PENDING");
        assertThat(row.get("payment_note")).isEqualTo("Не вдалося прочитати квитанцію.");

        HttpResponse<String> dismissed = post(
                "/api/projects/" + projectId + "/messages/" + row.get("id") + "/payment/dismiss");
        assertThat(dismissed.statusCode()).as(dismissed.body()).isEqualTo(200);
        assertThat(noticeRow().get("payment_status")).isEqualTo("DISMISSED");
        assertThat(post("/api/projects/" + projectId + "/messages/" + row.get("id") + "/payment/confirm")
                .statusCode()).isEqualTo(200);
        assertThat(moneyRows()).as("a dismissed notice is never recorded afterwards").isZero();
    }

    @Test
    void anOrdinaryMessageIsNoPaymentNotice() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO project_messages (id, project_id, message) VALUES (?, ?, 'Привіт')", id, projectId);

        assertThat(post("/api/projects/" + projectId + "/messages/" + id + "/payment/confirm").statusCode())
                .isEqualTo(404);
        assertThat(moneyRows()).isZero();
    }

    @Test
    void noNoticeThroughTheMessageLink_norWhereTheMasterOffersNoWayToPay() throws Exception {
        assertThat(notice(portalToken, "{\"amount\":100}", null).statusCode()).as("nothing to pay to").isEqualTo(404);

        jdbc.update("UPDATE users SET payout_card = ? WHERE id = ?", CARD, ownerId);

        assertThat(notice(messageToken, "{\"amount\":100}", null).statusCode()).isEqualTo(404);
        assertThat(notice(portalToken, "{\"amount\":0}", null).statusCode()).isEqualTo(400);
    }

    /** The CHECK is the one place the pairing is enforced for any writer (memory: a new enum value widens a CHECK). */
    @Test
    void aPaymentMessageMustCarryItsAmountAndState_andAnOrdinaryOneNeither() {
        for (String status : new String[] {"PENDING", "RECORDED", "DISMISSED"}) {
            jdbc.update("""
                    INSERT INTO project_messages (id, project_id, message, kind, payment_amount, payment_status)
                    VALUES (?, ?, 'ok', 'PAYMENT', 5, ?)""", UUID.randomUUID(), projectId, status);
        }
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO project_messages (id, project_id, message, kind, payment_status)
                VALUES (?, ?, 'x', 'PAYMENT', 'PENDING')""",
                UUID.randomUUID(), projectId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO project_messages (id, project_id, message, kind, payment_amount)
                VALUES (?, ?, 'x', 'PAYMENT', 5)""",
                UUID.randomUUID(), projectId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO project_messages (id, project_id, message, kind, payment_amount)
                VALUES (?, ?, 'x', 'MESSAGE', 5)""", UUID.randomUUID(), projectId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO project_messages (id, project_id, message, payment_status)
                VALUES (?, ?, 'x', 'PENDING')""", UUID.randomUUID(), projectId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- plumbing ------------------------------------------------------------------------------

    private HttpResponse<String> putRequisites(String json) throws Exception {
        return send(HttpRequest.newBuilder()
                .uri(uri("/api/profile/requisites"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)), true);
    }

    private HttpResponse<String> notice(String token, String json, byte[] png) throws Exception {
        String boundary = "----majstr" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"data\"; filename=\"blob\"\r\n"
                + "Content-Type: application/json\r\n\r\n" + json + "\r\n").getBytes(StandardCharsets.UTF_8));
        if (png != null) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"kvytantsia.png\"\r\n"
                    + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(png);
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(HttpRequest.newBuilder()
                .uri(uri("/api/public/payment-notice/" + token))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())), false);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return get(path, false);
    }

    private HttpResponse<String> get(String path, boolean authed) throws Exception {
        return send(HttpRequest.newBuilder().uri(uri(path)).GET(), authed);
    }

    private HttpResponse<String> post(String path) throws Exception {
        return send(HttpRequest.newBuilder().uri(uri(path)).POST(HttpRequest.BodyPublishers.noBody()), true);
    }

    private Map<String, Object> noticeRow() {
        return jdbc.queryForMap("""
                SELECT id, kind, payment_amount, message, payment_status, payment_note, payment_receipt_id
                FROM project_messages WHERE project_id = ?""", projectId);
    }

    private Integer moneyRows() {
        return jdbc.queryForObject("SELECT count(*) FROM payment_receipt WHERE project_id = ?", Integer.class, projectId);
    }

    private static LocalDate today() {
        return LocalDate.now(LocalizationConfig.ZONE);
    }

    private HttpResponse<byte[]> getBytes(String path) throws Exception {
        return http.send(HttpRequest.newBuilder().uri(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> send(HttpRequest.Builder req, boolean authed) throws Exception {
        if (authed) {
            req.header("Authorization", bearer);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
    }
}
