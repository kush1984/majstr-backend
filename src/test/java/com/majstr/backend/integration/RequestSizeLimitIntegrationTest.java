package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The ceiling, over a real socket: nothing in standalone MockMvc passes through the servlet filters. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestSizeLimitIntegrationTest extends IntegrationTestBase {

    @Autowired Environment env;

    private final HttpClient http = HttpClient.newHttpClient();

    private static byte[] jsonOfSize(int size) {
        byte[] padding = new byte[size];
        Arrays.fill(padding, (byte) ' ');
        byte[] json = "{\"email\":\"a@example.com\",\"password\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(json, 0, padding, 0, json.length);
        return padding;
    }

    private HttpRequest.Builder post(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + env.getProperty("local.server.port") + path))
                .header("Content-Type", "application/json");
    }

    private int status(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void anOversizedLoginIsRefusedWithAContentLength() throws Exception {
        assertThat(status(post("/api/auth/login")
                .POST(HttpRequest.BodyPublishers.ofByteArray(jsonOfSize(100 * 1024))).build()))
                .isEqualTo(413);
    }

    @Test
    void anOversizedLoginIsRefusedWhenChunked() throws Exception {
        byte[] body = jsonOfSize(100 * 1024);
        assertThat(status(post("/api/auth/login")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))).build()))
                .isEqualTo(413);
    }

    @Test
    void anOversizedWebhookIsRefused() throws Exception {
        assertThat(status(post("/api/billing/webhook")
                .POST(HttpRequest.BodyPublishers.ofByteArray(jsonOfSize(100 * 1024))).build()))
                .isEqualTo(413);
    }

    @Test
    void aChunkedBodyPastTheDefaultCeilingAnswers413NotMalformedJson() throws Exception {
        // Read by the message converter this time, so the advice has to recognise it.
        byte[] body = jsonOfSize(3 * 1024 * 1024);
        assertThat(status(post("/api/public/materials/calc")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))).build()))
                .isEqualTo(413);
    }

    @Test
    void anOrdinaryLoginIsUntouched() throws Exception {
        String body = "{\"email\":\"size-" + UUID.randomUUID() + "@example.com\",\"password\":\"wrong-password\"}";
        assertThat(status(post("/api/auth/login").POST(HttpRequest.BodyPublishers.ofString(body)).build()))
                .isEqualTo(401);
    }
}
