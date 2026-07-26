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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The security URL matrix, exercised through the REAL filter chain over a real socket.
 *
 * <p>Nothing tested this before: every controller test uses standalone MockMvc, which
 * contains no Spring Security at all, and {@code AdminAccessTest} only asserts that
 * {@code UserPrincipal} emits the right role strings. So a stray {@code /api/**} in
 * {@code PUBLIC_PATHS}, a dropped {@code hasRole("ADMIN")}, or a filter registered in the
 * wrong order would ship with the whole suite green and be found only by someone reaching
 * data they should not.</p>
 *
 * <p><b>Plain JDK {@link HttpClient}, deliberately.</b> Boot 4 removed the test-slice
 * annotations AND {@code TestRestTemplate}; its replacement moved packages more than once.
 * The JDK client and the {@code local.server.port} property are core, stable API, so this
 * test cannot rot the next time Boot reshuffles its test modules — and it exercises the
 * chain over a genuine socket, which is what is being asserted anyway.</p>
 *
 * <p>Assertions are about the GATE ("was I let in"), never a success body, and compare raw
 * status ints — {@code HttpStatusCode} vs {@code HttpStatus} casting is a needless trap.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityMatrixIntegrationTest extends IntegrationTestBase {

    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;
    private static final int NOT_FOUND = 404;

    @Autowired Environment env;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void protectedApiWithoutATokenIs401_noAccidentalPublicPath() throws Exception {
        for (String path : new String[]{
                "/api/projects", "/api/clients", "/api/estimates", "/api/catalog", "/api/profile",
        }) {
            assertThat(status(path, null))
                    .as("%s must require authentication", path)
                    .isEqualTo(UNAUTHORIZED);
        }
    }

    @Test
    void adminApiWithoutATokenIs401() throws Exception {
        assertThat(status("/api/admin/users", null)).isEqualTo(UNAUTHORIZED);
    }

    @Test
    void declaredPublicPathsAreNotRefusedByTheGate() throws Exception {
        // Not asserting 200 — several of these legitimately answer 400/404/405 once past the
        // filter. The point is only that Security did not stop them.
        for (String path : new String[]{
                "/api/push/vapid-public-key", "/actuator/health", "/robots.txt", "/favicon.ico",
        }) {
            assertThat(status(path, null))
                    .as("%s is declared public, so Security must not refuse it", path)
                    .isNotIn(UNAUTHORIZED, FORBIDDEN);
        }
    }

    @Test
    void aGarbageBearerTokenIsRejected_notTreatedAsAnonymous() throws Exception {
        // The JWT filter must FAIL a malformed token, not quietly fall through to "no user".
        assertThat(status("/api/projects", "Bearer not-a-jwt")).isEqualTo(UNAUTHORIZED);
    }

    @Test
    void anAuthenticatedNonAdminIs403OnAdminApi_notLoggedOut() throws Exception {
        // The OTHER half of the contract. 401 and 403 must not be interchangeable: 401 tells
        // the PWA "refresh the token", 403 tells it "this account may not". If a future change
        // made everything 401, a plain user hitting an admin URL would be silently logged out;
        // if it made everything 403, an expired session would never refresh. Both halves are
        // asserted so neither can drift.
        String token = tokenForFreshUser();

        assertThat(status("/api/admin/users", "Bearer " + token)).isEqualTo(FORBIDDEN);

        // Control: the same token is genuinely accepted elsewhere, so the 403 above is about
        // the ROLE — not a token this test failed to mint correctly.
        assertThat(status("/api/projects", "Bearer " + token)).isEqualTo(200);
    }

    @Test
    void aScannerProbeUnderAPermittedPrefixIsAQuiet404() throws Exception {
        // /admin/** is permitAll (the admin HTML lives there and does its own Bearer calls),
        // so a bot probing /admin/phpinfo.php reaches the DispatcherServlet and must get the
        // quiet 404 GlobalExceptionHandler maps NoResourceFoundException to — not a 500, and
        // not a Sentry event. Anything OUTSIDE such a prefix is 401 via
        // anyRequest().authenticated(), which is why this probes an admin path specifically.
        assertThat(status("/admin/phpinfo.php", null)).isEqualTo(NOT_FOUND);
    }

    // ---- helpers ----------------------------------------------------------------

    /** A real persisted USER-role account and a real signed access token for it. */
    private String tokenForFreshUser() {
        String unique = UUID.randomUUID().toString();
        String email = unique + "@majstr.test";
        User user = userRepository.save(User.builder()
                .email(email)
                .emailCanonical(email)
                .passwordHash("x")
                .fullName("Майстер")
                .phone("+380000000000")
                .companyName("ФОП")
                .plan(Plan.FREE)
                .role(Role.USER)
                .referralCode(unique.substring(0, 10)) // NOT NULL since V41, no entity default
                .build());
        return jwtService.generateAccessToken(user.getId(), user.getEmail());
    }

    /** GET the path on the running server; {@code authorization} may be null. */
    private int status(String path, String authorization) throws IOException, InterruptedException {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port() + path))
                .GET();
        if (authorization != null) {
            req.header("Authorization", authorization);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /** RANDOM_PORT publishes the chosen port as this property — no annotation needed. */
    private String port() {
        return env.getProperty("local.server.port");
    }
}
