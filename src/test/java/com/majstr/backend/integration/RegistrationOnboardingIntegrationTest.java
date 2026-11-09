package com.majstr.backend.integration;

import com.majstr.backend.entity.User;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.FakeDnsEmailPolicyConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registration friction round 2 (V190) through the real server: the door takes email + password +
 * consent, the trades come after and bring their catalog, and the name is asked for only when a
 * document is about to leave — {@code SENDER_NAME_REQUIRED} on the share link and the PDF until the
 * «як вас підписати» sheet has saved one. Since share-without-verification (2026-11-09) that name is
 * the ONLY bar for what the master sends himself; a letter we send still needs a verified email.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.forward-headers-strategy=framework")
@Import(FakeDnsEmailPolicyConfig.class)
class RegistrationOnboardingIntegrationTest extends IntegrationTestBase {

    @Autowired Environment env;
    @Autowired UserRepository users;
    @Autowired CatalogItemRepository catalog;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    private record Answer(int status, JsonNode body, byte[] raw) {
        String code() {
            return body == null || body.get("code") == null ? null : body.get("code").asString();
        }
    }

    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    private Answer call(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + env.getProperty("local.server.port") + path))
                .header("Accept-Language", "uk")
                .header("X-Forwarded-For", "203.0.113." + NEXT_IP.getAndIncrement())
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<byte[]> response = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        byte[] raw = response.body();
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        JsonNode node = raw.length > 0 && contentType.contains("json")
                ? json.readTree(new String(raw, StandardCharsets.UTF_8)) : null;
        return new Answer(response.statusCode(), node, raw);
    }

    private static String fresh() {
        return "onb-" + UUID.randomUUID() + "@ukr.net";
    }

    /** What the new PWA sends: three things. */
    private String registerBare(String email) throws Exception {
        Answer a = call("POST", "/api/auth/register", null,
                "{\"email\":\"%s\",\"password\":\"Sup3r-Secret!\",\"consent\":true}".formatted(email));
        assertThat(a.status()).as("bare registration").isEqualTo(201);
        return a.body().get("accessToken").asString();
    }

    @Test
    void theDoorTakesEmailPasswordConsent_andInventsNothing() throws Exception {
        String email = fresh();
        String token = registerBare(email);

        User user = users.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(user.getFullName()).isNull();
        assertThat(user.getPhone()).isNull();
        assertThat(user.getCompanyName()).isNull();
        assertThat(catalog.countByOwnerId(user.getId())).as("no trade, no starter catalog").isZero();

        Answer me = call("GET", "/api/auth/me", token, null);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().get("trades")).isEmpty();
        assertThat(call("GET", "/api/estimate-templates", token, null).body())
                .as("no trade, no default bundles").isEmpty();
    }

    @Test
    void consentIsStillRequiredAtTheDoor() throws Exception {
        Answer a = call("POST", "/api/auth/register", null,
                "{\"email\":\"%s\",\"password\":\"Sup3r-Secret!\",\"consent\":false}".formatted(fresh()));
        assertThat(a.status()).isEqualTo(400);
    }

    @Test
    void pickingTradesAfterwards_fillsTheCatalog_andShowsTheirBundles() throws Exception {
        String email = fresh();
        String token = registerBare(email);
        UUID id = users.findByEmailIgnoreCase(email).orElseThrow().getId();

        Answer saved = call("PUT", "/api/profile/trades", token, "{\"trades\":[\"TILING\"]}");

        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.body().get("trades").get(0).asString()).isEqualTo("TILING");
        long tiling = catalog.countByOwnerId(id);
        assertThat(tiling).as("the tiling starter set arrived unasked").isPositive();
        assertThat(call("GET", "/api/estimate-templates", token, null).body()).isNotEmpty();

        // The same trade again adds nothing; the merge never duplicates.
        call("PUT", "/api/profile/trades", token, "{\"trades\":[\"TILING\"]}");
        assertThat(catalog.countByOwnerId(id)).isEqualTo(tiling);

        // A skip is legal and removes nothing he already has.
        assertThat(call("PUT", "/api/profile/trades", token, "{\"trades\":[]}").status()).isEqualTo(200);
        assertThat(catalog.countByOwnerId(id)).isEqualTo(tiling);
    }

    @Test
    void theProfileSavesWithoutAName_andAnAddedTradeBringsItsCatalog() throws Exception {
        String email = fresh();
        String token = registerBare(email);
        UUID id = users.findByEmailIgnoreCase(email).orElseThrow().getId();

        Answer saved = call("PUT", "/api/profile", token,
                "{\"fullName\":\"\",\"phone\":\"\",\"companyName\":\"\",\"trades\":[\"PAINTER\"],\"email\":\"%s\"}"
                        .formatted(email));

        assertThat(saved.status()).isEqualTo(200);
        assertThat(catalog.countByOwnerId(id)).as("no «додати набір?» question any more").isPositive();
    }

    @Test
    void aDocumentWaitsForAName_thenLeaves() throws Exception {
        String email = fresh();
        String token = registerBare(email);
        User user = users.findByEmailIgnoreCase(email).orElseThrow();
        user.setEmailVerified(true);
        users.save(user);

        String projectId = call("POST", "/api/projects", token,
                "{\"name\":\"Обʼєкт\",\"address\":\"вул. Тестова 1\"}").body().get("id").asString();
        String estimateId = call("POST", "/api/projects/" + projectId + "/estimates", token,
                "{\"name\":\"Кошторис\"}").body().get("id").asString();

        Answer share = call("POST", "/api/estimates/" + estimateId + "/share", token, null);
        assertThat(share.status()).isEqualTo(403);
        assertThat(share.code()).isEqualTo("SENDER_NAME_REQUIRED");
        assertThat(share.body().get("message").asString()).isEqualTo("Вкажіть, як вас підписати в документі");

        Answer portal = call("PUT", "/api/projects/" + projectId + "/portal", token,
                "{\"estimateIds\":[\"%s\"]}".formatted(estimateId));
        assertThat(portal.code()).as("the object portal waits too").isEqualTo("SENDER_NAME_REQUIRED");

        Answer pdf = call("GET", "/api/estimates/" + estimateId + "/pdf", token, null);
        assertThat(pdf.status()).isEqualTo(403);

        Answer sender = call("PATCH", "/api/profile/sender", token, "{\"fullName\":\"Іван Петренко\"}");
        assertThat(sender.status()).isEqualTo(200);
        assertThat(sender.body().get("fullName").asString()).isEqualTo("Іван Петренко");

        assertThat(call("POST", "/api/estimates/" + estimateId + "/share", token, null).status())
                .isBetween(200, 201);
        Answer printed = call("GET", "/api/estimates/" + estimateId + "/pdf", token, null);
        assertThat(printed.status()).isEqualTo(200);
        String text;
        try (var doc = org.apache.pdfbox.Loader.loadPDF(printed.raw())) {
            text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
        }
        assertThat(text).contains("Іван Петренко").doesNotContain("null").doesNotContain("Тел.");
    }

    /** V191: «Залиште телефон — передзвонимо» saves the phone and stamps the ask once. */
    @Test
    void askingForACall_savesThePhone_andKeepsTheFirstAsk() throws Exception {
        String email = fresh();
        String token = registerBare(email);

        Answer first = call("POST", "/api/profile/help-request", token, "{\"phone\":\"+380501112233\"}");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("phone")).as("a call number is never a document's phone (V193)").isNull();
        var stamped = users.findByEmailIgnoreCase(email).orElseThrow().getHelpRequestedAt();
        assertThat(stamped).isNotNull();

        call("POST", "/api/profile/help-request", token, "{\"phone\":\"+380670000000\"}");
        User after = users.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(after.getHelpRequestedAt()).isEqualTo(stamped);
        assertThat(after.getHelpPhone()).isEqualTo("+380670000000");
        assertThat(after.getPhone()).isNull();

        assertThat(call("POST", "/api/profile/help-request", token, "{\"phone\":\" \"}").status()).isEqualTo(400);
    }

    /** V192: an object needs a name, not an address — and the address can be added on edit. */
    @Test
    void anObjectNeedsANameOnly_andTheAddressComesLater() throws Exception {
        String token = registerBare(fresh());

        assertThat(call("POST", "/api/projects", token, "{\"name\":\"  \"}").status())
                .as("a name is still required").isEqualTo(400);
        Answer created = call("POST", "/api/projects", token, "{\"name\":\"Ванна\"}");
        assertThat(created.status()).isBetween(200, 201);
        assertThat(created.body().get("address")).as("absent while empty").isNull();
        String id = created.body().get("id").asString();

        Answer edited = call("PUT", "/api/projects/" + id, token, "{\"name\":\"Ванна\",\"address\":\"вул. Нова 3\"}");
        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.body().get("address").asString()).isEqualTo("вул. Нова 3");

        Answer cleared = call("PUT", "/api/projects/" + id, token, "{\"name\":\"Ванна\",\"address\":\"\"}");
        assertThat(cleared.body().get("address")).isNull();
    }

    /** V193: the answer to «Чим займаєтесь?» is the server's — a skip too — and unticking later keeps it. */
    @Test
    void answeringTheTradesScreen_isStampedOnTheServer_aSkipIncluded() throws Exception {
        String email = fresh();
        String token = registerBare(email);
        assertThat(call("GET", "/api/auth/me", token, null).body().get("tradesOnboardedAt")).isNull();

        call("PUT", "/api/profile/trades", token, "{\"trades\":[]}");

        assertThat(call("GET", "/api/auth/me", token, null).body().get("tradesOnboardedAt")).isNotNull();
        assertThat(users.findByEmailIgnoreCase(email).orElseThrow().getTradesOnboardedAt()).isNotNull();
    }

    /** Review: a name once given can be changed, not erased — documents may already carry it. */
    @Test
    void aGivenNameCannotBeErasedInTheProfile() throws Exception {
        String email = fresh();
        String token = registerBare(email);
        call("PATCH", "/api/profile/sender", token, "{\"fullName\":\"Іван\"}");

        Answer erased = call("PUT", "/api/profile", token,
                "{\"fullName\":\"\",\"trades\":[],\"email\":\"%s\"}".formatted(email));

        assertThat(erased.status()).isEqualTo(400);
        assertThat(erased.code()).isEqualTo("PROFILE_NAME_REQUIRED");
        assertThat(users.findByEmailIgnoreCase(email).orElseThrow().getFullName()).isEqualTo("Іван");
    }

    @Test
    void theSenderSheetRefusesABlankName() throws Exception {
        String token = registerBare(fresh());
        assertThat(call("PATCH", "/api/profile/sender", token, "{\"fullName\":\"  \"}").status()).isEqualTo(400);
    }

    /**
     * Share without verification (2026-11-09): an unverified master with a name mints the link, marks
     * it sent, publishes the object portal and downloads the PDF; every letter WE send, the PRO trial
     * and checkout still answer 403 {@code EMAIL_NOT_VERIFIED}.
     */
    @Test
    void anUnverifiedMasterSendsHimself_butNotThroughOurLetters() throws Exception {
        String email = fresh();
        String token = registerBare(email);
        assertThat(users.findByEmailIgnoreCase(email).orElseThrow().isEmailVerified()).isFalse();
        assertThat(call("PATCH", "/api/profile/sender", token, "{\"fullName\":\"Іван Петренко\"}").status())
                .isEqualTo(200);

        String clientId = call("POST", "/api/clients", token,
                "{\"fullName\":\"Олена\",\"phone\":\"+380501234567\",\"email\":\"olena-%s@ukr.net\"}".formatted(UUID.randomUUID()))
                .body().get("id").asString();
        String projectId = call("POST", "/api/projects", token,
                "{\"name\":\"Обʼєкт\",\"clientId\":\"%s\"}".formatted(clientId)).body().get("id").asString();
        String estimateId = call("POST", "/api/projects/" + projectId + "/estimates", token,
                "{\"name\":\"Кошторис\"}").body().get("id").asString();

        assertThat(call("POST", "/api/estimates/" + estimateId + "/share", token, null).status())
                .as("mint the link").isBetween(200, 201);
        assertThat(call("POST", "/api/estimates/" + estimateId + "/share/mark-sent", token, null).status())
                .as("the link left").isBetween(200, 204);
        assertThat(call("PUT", "/api/projects/" + projectId + "/portal", token,
                "{\"estimateIds\":[\"%s\"]}".formatted(estimateId)).status()).as("object portal").isEqualTo(200);
        assertThat(call("POST", "/api/projects/" + projectId + "/portal/mark-sent", token, null).status())
                .isBetween(200, 204);
        assertThat(call("GET", "/api/estimates/" + estimateId + "/pdf", token, null).status())
                .as("the PDF").isEqualTo(200);

        for (String path : new String[] {
                "/api/estimates/" + estimateId + "/share/send-email",
                "/api/projects/" + projectId + "/portal/send-email",
                "/api/billing/trial"}) {
            Answer refused = call("POST", path, token, null);
            assertThat(refused.status()).as(path).isEqualTo(403);
            assertThat(refused.code()).as(path).isEqualTo("EMAIL_NOT_VERIFIED");
        }
        Answer checkout = call("POST", "/api/billing/checkout", token, "{\"period\":\"MONTH\",\"autoRenew\":false}");
        assertThat(checkout.code()).as("checkout").isEqualTo("EMAIL_NOT_VERIFIED");

        Answer limits = call("GET", "/api/plan/limits", token, null);
        assertThat(limits.body().get("trialDays").asInt())
                .as("the trial offer names the configured length")
                .isEqualTo(env.getProperty("app.billing.trial-days", Integer.class));
    }
}
