package com.majstr.backend.integration;

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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registration through the real server: the filter chain, the rate limiter in front of it, the email policy
 * and the error handler, over HTTP. This is the door every master comes in by; a break here costs every
 * registration, so it is pinned end to end and not only in pieces.
 *
 * <p>Each test registers from its own address ({@code X-Forwarded-For}, trusted the way production trusts
 * the edge), so the limiter of five per hour counts each test on its own.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.forward-headers-strategy=framework")
@Import(FakeDnsEmailPolicyConfig.class)
class RegistrationEmailIntegrationTest extends IntegrationTestBase {

    private static final int TRIES_PER_HOUR = 5;
    private static final int REFUNDS_PER_HOUR = 20;

    @Autowired Environment env;
    @Autowired UserRepository users;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    private record Answer(int status, JsonNode body) {}

    private Answer register(String ip, String email) throws Exception {
        String body = """
                {"email":"%s","password":"Sup3r-Secret!","fullName":"Іван Петренко","trades":["TILING"],
                 "phone":"+380501112233","companyName":"Іван Петренко","consent":true}
                """.formatted(email);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + env.getProperty("local.server.port") + "/api/auth/register"))
                .header("Content-Type", "application/json")
                .header("Accept-Language", "uk")
                .header("X-Forwarded-For", ip)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String text = response.body();
        return new Answer(response.statusCode(), text == null || text.isBlank() ? null : json.readTree(text));
    }

    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    /** One documentation-range address per test: the limiter is keyed by it. */
    private static String ip() {
        return "198.51.100." + NEXT_IP.getAndIncrement();
    }

    private static String fresh(String domain) {
        return "reg-" + UUID.randomUUID() + "@" + domain;
    }

    @Test
    void aRealAddressRegisters() throws Exception {
        String email = fresh("ukr.net");

        Answer answer = register(ip(), email);

        assertThat(answer.status()).isEqualTo(201);
        assertThat(answer.body().get("accessToken").asString()).isNotBlank();
        assertThat(users.findByEmailIgnoreCase(email)).isPresent();
    }

    @Test
    void aThrowawayIsRefusedInWordsThatSayItIsAThrowaway() throws Exception {
        String email = fresh("yopmail.com");

        Answer answer = register(ip(), email);

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.body().get("code").asString()).isEqualTo("EMAIL_DISPOSABLE");
        assertThat(answer.body().get("message").asString()).startsWith("Тимчасова пошта не підійде");
        assertThat(users.findByEmailIgnoreCase(email)).isEmpty();
    }

    /** gmal.com takes no mail and gmail.con does not exist: both are a typo, and say so. */
    @Test
    void aTypoInTheDomainIsRefusedAsATypo() throws Exception {
        for (String domain : new String[]{"gmal.com", "gmail.con"}) {
            String email = fresh(domain);

            Answer answer = register(ip(), email);

            assertThat(answer.status()).as(domain).isEqualTo(400);
            assertThat(answer.body().get("code").asString()).as(domain).isEqualTo("EMAIL_NO_MAIL");
            assertThat(answer.body().get("message").asString()).as(domain).startsWith("Схоже, в адресі помилка");
            assertThat(users.findByEmailIgnoreCase(email)).as(domain).isEmpty();
        }
    }

    /** What happened on 2026-11-06: refused, pressed again and again. Now the person still gets in. */
    @Test
    void refusedAttemptsDoNotSpendTheTries() throws Exception {
        String ip = ip();
        for (int i = 0; i < TRIES_PER_HOUR + 3; i++) {
            assertThat(register(ip, fresh("yopmail.com")).status()).as("refused attempt %d", i + 1).isEqualTo(400);
        }

        assertThat(register(ip, fresh("ukr.net")).status()).isEqualTo(201);
    }

    /** «This address has an account» counts, or the endpoint would test addresses for free. */
    @Test
    void aTakenAddressStillCountsTowardsTheLimit() throws Exception {
        String ip = ip();
        String email = fresh("ukr.net");
        assertThat(register(ip, email).status()).isEqualTo(201);
        for (int i = 2; i <= TRIES_PER_HOUR; i++) {
            assertThat(register(ip, email).status()).as("attempt %d", i).isEqualTo(409);
        }

        Answer blocked = register(ip, email);

        assertThat(blocked.status()).isEqualTo(429);
    }

    /** A script cycling bad addresses still runs out: the refunds have their own cap. */
    @Test
    void refundsRunOut() throws Exception {
        String ip = ip();
        for (int i = 0; i < TRIES_PER_HOUR + REFUNDS_PER_HOUR; i++) {
            assertThat(register(ip, fresh("yopmail.com")).status()).as("attempt %d", i + 1).isEqualTo(400);
        }

        assertThat(register(ip, fresh("ukr.net")).status()).isEqualTo(429);
    }
}
