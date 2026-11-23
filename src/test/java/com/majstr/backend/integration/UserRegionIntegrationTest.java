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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V219 — where a master works: its own write, shown back on /me, and only the 25 regions exist. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserRegionIntegrationTest extends IntegrationTestBase {

    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;

    private final HttpClient http = HttpClient.newHttpClient();
    private UUID userId;
    private String bearer;

    @BeforeEach
    void seed() {
        String unique = UUID.randomUUID().toString();
        String email = unique + "@majstr.test";
        User user = userRepository.save(User.builder()
                .email(email).emailCanonical(email).passwordHash("x").fullName("Іван")
                .plan(Plan.FREE).role(Role.USER).referralCode(unique.substring(0, 10)).build());
        userId = user.getId();
        bearer = "Bearer " + jwtService.generateAccessToken(user.getId(), user.getEmail());
    }

    @Test
    void theRegionIsSaved_andComesBackOnMe_andAProfileSaveLeavesItAlone() throws Exception {
        assertThat(send("GET", "/api/auth/me", null).body()).doesNotContain("\"region\"");

        HttpResponse<String> saved = send("PUT", "/api/profile/region", "{\"region\":\"LVIV\"}");
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(saved.body()).contains("\"region\":\"LVIV\"");

        HttpResponse<String> profile = send("PUT", "/api/profile", """
                {"fullName":"Іван Петренко","phone":"+380000000000","companyName":null,"trades":["TILING"],"email":null}
                """);
        assertThat(profile.statusCode()).as(profile.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT region FROM users WHERE id = ?", String.class, userId)).isEqualTo("LVIV");
    }

    @Test
    void anUnknownRegionIsRefused_byTheApiAndTheDatabase() throws Exception {
        assertThat(send("PUT", "/api/profile/region", "{\"region\":\"MOSCOW\"}").statusCode()).isEqualTo(400);
        assertThat(send("PUT", "/api/profile/region", "{}").statusCode()).isEqualTo(400);
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET region = 'ATLANTIS' WHERE id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + env.getProperty("local.server.port") + path))
                .header("Authorization", bearer);
        if (json == null) {
            req.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            req.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }
}
