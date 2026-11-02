package com.majstr.backend.integration;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.Project;
import com.majstr.backend.repository.ProjectPhotoRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.security.IdempotentRequestStore;
import com.majstr.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Over a real socket, through the real chain. Review B-129: a malformed request is the caller's mistake and
 * says so. Review P-78: a repeated submission does not run twice.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestErrorsAndRepeatsIntegrationTest extends IntegrationTestBase {

    @Autowired Environment env;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectPhotoRepository photoRepository;
    @Autowired IdempotentRequestStore store;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private User owner;
    private Project project;
    private String bearer;

    @BeforeEach
    void seed() {
        String u = UUID.randomUUID().toString();
        owner = userRepository.save(User.builder()
                .email(u + "@majstr.test").emailCanonical(u + "@majstr.test").passwordHash("x")
                .fullName("Master").phone("+380000000000").companyName("Company")
                .plan(Plan.PRO).role(Role.USER).referralCode(u.substring(0, 10)).build());
        project = projectRepository.save(Project.builder().owner(owner)
                .name("Object").address("Street 1").status(ProjectStatus.IN_PROGRESS).build());
        bearer = "Bearer " + jwtService.generateAccessToken(owner.getId(), owner.getEmail());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + env.getProperty("local.server.port") + path))
                .header("Authorization", bearer);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ---- B-129 ---------------------------------------------------------------------------------

    @Test
    void aWrongMethodIs405WithTheAllowedOnes() throws Exception {
        HttpResponse<String> response = send(request("/api/auth/me").DELETE().build());

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Allow")).isPresent();
        assertThat(response.body()).contains("\"status\":405");
    }

    @Test
    void anIdThatIsNotAUuidIs400() throws Exception {
        HttpResponse<String> response = send(request("/api/projects/not-a-uuid").GET().build());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"status\":400");
    }

    @Test
    void aMissingRequiredFileIs400() throws Exception {
        String boundary = "----test";
        HttpResponse<String> response = send(request("/api/projects/" + project.getId() + "/photos")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString("--" + boundary + "--\r\n")).build());

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void theWrongContentTypeIs415() throws Exception {
        HttpResponse<String> response = send(request("/api/projects/" + project.getId() + "/photos")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build());

        assertThat(response.statusCode()).isEqualTo(415);
    }

    // ---- P-78 ----------------------------------------------------------------------------------

    private HttpRequest upload(String key) throws Exception {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpeg", jpeg);
        String boundary = "----test";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"source\"\r\n\r\nMANUAL\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(jpeg.toByteArray());
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder builder = request("/api/projects/" + project.getId() + "/photos")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (key != null) {
            builder.header("X-Entity-Uuid", key);
        }
        return builder.build();
    }

    private long photos() {
        return photoRepository.findByProjectIdOrderByCreatedAtDesc(project.getId()).size();
    }

    @Test
    void aRepeatedUploadAnswersTheSame_andStoresOnePhoto() throws Exception {
        String key = UUID.randomUUID().toString();

        HttpResponse<String> first = send(upload(key));
        HttpResponse<String> second = send(upload(key));

        assertThat(first.statusCode()).isBetween(200, 201);
        assertThat(second.statusCode()).isEqualTo(first.statusCode());
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(photos()).isEqualTo(1);
    }

    @Test
    void anotherSubmissionIsAnotherPhoto() throws Exception {
        send(upload(UUID.randomUUID().toString()));
        send(upload(UUID.randomUUID().toString()));

        assertThat(photos()).isEqualTo(2);
    }

    @Test
    void aRequestWithNoKeyIsUntouched() throws Exception {
        send(upload(null));
        send(upload(null));

        assertThat(photos()).isEqualTo(2);
    }

    @Test
    void whileTheFirstAttemptIsStillRunningARetryIsToldToWait() throws Exception {
        UUID key = UUID.randomUUID();
        store.claim(owner.getId(), key, "/api/projects/" + project.getId() + "/photos");

        HttpResponse<String> response = send(upload(key.toString()));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("REQUEST_IN_FLIGHT");
        assertThat(response.headers().firstValue("Retry-After")).isPresent();
        assertThat(photos()).isZero();
    }

    @Test
    void aFailedAttemptIsNotRemembered_soTheRetryReallyRuns() throws Exception {
        String key = UUID.randomUUID().toString();
        String boundary = "----test";
        HttpResponse<String> failed = send(request("/api/projects/" + project.getId() + "/photos")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-Entity-Uuid", key)
                .POST(HttpRequest.BodyPublishers.ofString("--" + boundary + "--\r\n")).build());
        assertThat(failed.statusCode()).isEqualTo(400);

        HttpResponse<String> retried = send(upload(key));

        assertThat(retried.statusCode()).isBetween(200, 201);
        assertThat(photos()).isEqualTo(1);
    }

    @Test
    void anAttemptLeftBehindByACrashCanBeTakenOver() throws Exception {
        UUID key = UUID.randomUUID();
        String path = "/api/projects/" + project.getId() + "/photos";
        store.claim(owner.getId(), key, path);
        jdbc.update("UPDATE idempotent_request SET created_at = now() - interval '1 hour' WHERE request_key = ?", key);

        assertThat(send(upload(key.toString())).statusCode()).isBetween(200, 201);
    }

    @Test
    void oneMastersKeyMeansNothingToAnother() throws Exception {
        String key = UUID.randomUUID().toString();
        send(upload(key));
        String u = UUID.randomUUID().toString();
        User other = userRepository.save(User.builder()
                .email(u + "@majstr.test").emailCanonical(u + "@majstr.test").passwordHash("x")
                .fullName("Other").phone("+380000000001").companyName("Other")
                .plan(Plan.PRO).role(Role.USER).referralCode(u.substring(0, 10)).build());
        bearer = "Bearer " + jwtService.generateAccessToken(other.getId(), other.getEmail());

        // Their request runs on its own, and is refused for what it is: not their object.
        assertThat(send(upload(key)).statusCode()).isIn(403, 404);
    }

    @Test
    void theDailySweepDropsOldAnswers() {
        UUID key = UUID.randomUUID();
        store.claim(owner.getId(), key, "/x");
        store.complete(owner.getId(), key, "/x", 200, "application/json", new byte[]{1});
        jdbc.update("UPDATE idempotent_request SET created_at = now() - interval '2 days' WHERE request_key = ?", key);

        store.sweep();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotent_request WHERE request_key = ?",
                Long.class, key)).isZero();
    }
}
