package com.majstr.backend.integration;

import com.majstr.backend.dto.LoginRequest;
import com.majstr.backend.entity.PasswordResetToken;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.InvalidTokenException;
import com.majstr.backend.repository.PasswordResetTokenRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.security.JwtService;
import com.majstr.backend.security.TokenHash;
import com.majstr.backend.service.AuthService;
import com.majstr.backend.service.PasswordResetService;
import com.majstr.backend.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Review B-126, on real rows: what a replayed, a rotated and a reset session may still do. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionLifecycleIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "Sup3rPass!";

    @Autowired AuthService authService;
    @Autowired RefreshTokenService refreshTokens;
    @Autowired PasswordResetService passwordReset;
    @Autowired PasswordResetTokenRepository resetTokens;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment env;

    private final HttpClient http = HttpClient.newHttpClient();

    private User user;

    @BeforeEach
    void seed() {
        String u = UUID.randomUUID().toString();
        user = userRepository.save(User.builder()
                .email(u + "@majstr.test").emailCanonical(u + "@majstr.test")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .fullName("Master").phone("+380000000000").companyName("Company")
                .plan(Plan.FREE).role(Role.USER).referralCode(u.substring(0, 10)).build());
    }

    private String login() {
        return authService.login(new LoginRequest(user.getEmail(), PASSWORD)).refreshToken();
    }

    private String rotate(String token) {
        return refreshTokens.rotate(token).newRefreshToken();
    }

    private void agePastTheGraceWindow() {
        jdbc.update("UPDATE refresh_tokens SET rotated_at = ? WHERE user_id = ? AND rotated_at IS NOT NULL",
                Timestamp.from(Instant.now().minusSeconds(3600)), user.getId());
    }

    private int meStatus(String accessToken) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + env.getProperty("local.server.port") + "/api/auth/me"))
                .header("Authorization", "Bearer " + accessToken).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void aRotationKeepsTheChainWorking() {
        String first = login();
        String second = rotate(first);
        String third = rotate(second);

        assertThat(rotate(third)).isNotBlank();
    }

    @Test
    void aLostReplyIsForgiven_butOnlyAFewTimes() {
        String first = login();
        rotate(first);

        assertThat(rotate(first)).isNotBlank();
        assertThat(rotate(first)).isNotBlank();
        assertThatThrownBy(() -> rotate(first)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void aReplayPastTheWindowEndsTheWholeChain() {
        String stolen = login();
        String thiefsChild = rotate(stolen);
        String thiefsGrandchild = rotate(thiefsChild);
        agePastTheGraceWindow();

        assertThatThrownBy(() -> rotate(stolen)).isInstanceOf(InvalidTokenException.class);

        // The refusal rolled its own transaction back; the revocation must still have landed.
        assertThatThrownBy(() -> rotate(thiefsGrandchild)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void anotherLoginIsAnotherFamily_andSurvivesAReplayInTheFirst() {
        String stolen = login();
        rotate(stolen);
        String otherDevice = login();
        agePastTheGraceWindow();

        assertThatThrownBy(() -> rotate(stolen)).isInstanceOf(InvalidTokenException.class);

        assertThat(rotate(otherDevice)).isNotBlank();
    }

    @Test
    void logoutEndsTheChain_aTokenRotatedAMomentAgoIncluded() {
        String parent = login();
        String child = rotate(parent);

        refreshTokens.revoke(child);

        assertThatThrownBy(() -> rotate(child)).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> rotate(parent)).isInstanceOf(InvalidTokenException.class);
    }

    private String resetToken() {
        String raw = "reset-" + UUID.randomUUID();
        resetTokens.save(PasswordResetToken.builder()
                .user(userRepository.findById(user.getId()).orElseThrow())
                .tokenHash(TokenHash.of(raw))
                .expiresAt(Instant.now().plusSeconds(600))
                .build());
        return raw;
    }

    @Test
    void aPasswordReset_cannotBeOutrunByATokenRotatedAMomentAgo_andEndsAccessTokensToo() throws Exception {
        String parent = login();
        String child = rotate(parent);
        String access = jwtService.generateAccessToken(user.getId(), user.getEmail());
        assertThat(meStatus(access)).isEqualTo(200);
        Thread.sleep(1100); // iat has whole-second resolution

        passwordReset.reset(resetToken(), "An0therPass!");

        assertThat(meStatus(access)).isEqualTo(401);
        assertThatThrownBy(() -> rotate(parent)).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> rotate(child)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void aLoginAfterTheResetWorks() throws Exception {
        passwordReset.reset(resetToken(), "An0therPass!");

        var session = authService.login(new LoginRequest(user.getEmail(), "An0therPass!"));

        assertThat(meStatus(session.accessToken())).isEqualTo(200);
        assertThat(rotate(session.refreshToken())).isNotBlank();
    }
}
