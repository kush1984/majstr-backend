package com.majstr.backend.integration;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Review B-125: the import endpoints, including the title pass, share one per-account hourly limit. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rate-limit.ai-import.max-per-hour=2")
class AiImportLimitIntegrationTest extends IntegrationTestBase {

    @Autowired Environment env;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;

    private final HttpClient http = HttpClient.newHttpClient();

    private String bearer() {
        String u = UUID.randomUUID().toString();
        User user = userRepository.save(User.builder()
                .email(u + "@majstr.test").emailCanonical(u + "@majstr.test").passwordHash("x")
                .fullName("Master").phone("+380000000000").companyName("Company")
                .plan(Plan.PRO).role(Role.USER).referralCode(u.substring(0, 10)).build());
        return "Bearer " + jwtService.generateAccessToken(user.getId(), user.getEmail());
    }

    private HttpResponse<String> triage(String bearer) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + env.getProperty("local.server.port")
                        + "/api/projects/" + UUID.randomUUID() + "/measurements/project/triage"))
                .header("Authorization", bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void pastTheHourlyLimitAnAccountIsToldToWait_andAnotherIsNot() throws Exception {
        String busy = bearer();
        assertThat(triage(busy).statusCode()).isNotEqualTo(429);
        assertThat(triage(busy).statusCode()).isNotEqualTo(429);

        HttpResponse<String> refused = triage(busy);

        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(refused.headers().firstValue("Retry-After")).isPresent();
        assertThat(triage(bearer()).statusCode()).isNotEqualTo(429);
    }
}
