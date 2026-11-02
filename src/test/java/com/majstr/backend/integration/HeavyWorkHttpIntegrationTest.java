package com.majstr.backend.integration;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.PhotoVisibility;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.ShareLinkKind;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectShareLink;
import com.majstr.backend.entity.PhotoSource;
import com.majstr.backend.dto.ProjectPhotoResponse;
import com.majstr.backend.exception.ServerBusyException;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.ProjectShareLinkRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.security.JwtService;
import com.majstr.backend.service.HeavyWork;
import com.majstr.backend.service.ProjectPhotoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockMultipartFile;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Review B-122 and the streaming half of B-121, over a real socket and the real interceptor chain. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HeavyWorkHttpIntegrationTest extends IntegrationTestBase {

    private static final int IMPORT_PERMITS = 3;

    @Autowired Environment env;
    @Autowired HeavyWork heavyWork;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectShareLinkRepository shareLinks;
    @Autowired ProjectPhotoService photos;
    @Autowired JwtService jwtService;

    private final HttpClient http = HttpClient.newHttpClient();

    private User owner;
    private String bearer;

    @BeforeEach
    void seed() {
        String u = UUID.randomUUID().toString();
        owner = userRepository.save(User.builder()
                .email(u + "@majstr.test").emailCanonical(u + "@majstr.test").passwordHash("x")
                .fullName("Master").phone("+380000000000").companyName("Company")
                .plan(Plan.PRO).role(Role.USER).referralCode(u.substring(0, 10)).build());
        bearer = "Bearer " + jwtService.generateAccessToken(owner.getId(), owner.getEmail());
    }

    private HttpRequest importRequest() {
        String boundary = "----test" + UUID.randomUUID();
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"a.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\nname,price\r\n--" + boundary + "--\r\n";
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + env.getProperty("local.server.port") + "/api/estimates/import/parse"))
                .header("Authorization", bearer)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    @Test
    void aFullImportGateAnswers503WithARetryHint_andAFreedOneLetsTheRequestThrough() throws Exception {
        for (int i = 0; i < IMPORT_PERMITS; i++) {
            heavyWork.enterImport();
        }
        HttpResponse<String> busy;
        try {
            busy = http.send(importRequest(), HttpResponse.BodyHandlers.ofString());
        } finally {
            for (int i = 0; i < IMPORT_PERMITS; i++) {
                heavyWork.leaveImport();
            }
        }

        assertThat(busy.statusCode()).isEqualTo(503);
        assertThat(busy.headers().firstValue("Retry-After")).contains("30");
        assertThat(busy.body()).contains("SERVER_BUSY");

        assertThat(http.send(importRequest(), HttpResponse.BodyHandlers.ofString()).body())
                .doesNotContain("SERVER_BUSY");
    }

    @Test
    void aFinishedImportGivesItsPermitBack_whateverItAnswered() throws Exception {
        for (int i = 0; i < IMPORT_PERMITS * 2; i++) {
            http.send(importRequest(), HttpResponse.BodyHandlers.discarding());
        }

        for (int i = 0; i < IMPORT_PERMITS; i++) {
            heavyWork.enterImport();
        }
        try {
            assertThatThrownBy(heavyWork::enterImport).isInstanceOf(ServerBusyException.class);
        } finally {
            for (int i = 0; i < IMPORT_PERMITS; i++) {
                heavyWork.leaveImport();
            }
        }
    }

    @Test
    void aPortalPhotoReachesTheClientWholeAndWithItsType() throws Exception {
        Project project = projectRepository.save(Project.builder().owner(owner)
                .name("Object").address("Street 1").status(ProjectStatus.IN_PROGRESS).build());
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "jpeg", jpeg);
        ProjectPhotoResponse photo = photos.upload(project.getId(), owner.getId(),
                new MockMultipartFile("file", "a.jpg", "image/jpeg", jpeg.toByteArray()),
                PhotoSource.MANUAL, null, null, null);
        photos.setVisibility(project.getId(), photo.id(), owner.getId(), PhotoVisibility.SHARED);
        String token = shareLinks.save(ProjectShareLink.builder().project(project)
                .token("prt-" + UUID.randomUUID()).kind(ShareLinkKind.PORTAL).revoked(false).build()).getToken();

        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + env.getProperty("local.server.port")
                        + "/api/public/portal/" + token + "/photos/" + photo.id() + "/file"))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("image/jpeg");
        assertThat(response.body()).isEqualTo(jpeg.toByteArray());
    }
}
