package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

// JDK HttpClient never negotiates Accept-Encoding on its own, so comparing raw bytes is honest.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResponseCompressionIntegrationTest extends IntegrationTestBase {

    private static final String TILE_REQUEST = """
            {"calculator":"TILE","inputs":{"area":9500,"format":"T300X300","layout":"DIAGONAL","boxArea":1.44}}
            """;

    @Autowired Environment env;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void aRealisticCalcAnswerIsServedGzippedWhenTheClientAcceptsIt() throws Exception {
        HttpResponse<byte[]> plain = send(false);
        assertThat(plain.statusCode()).as("baseline call: %s", new String(plain.body())).isEqualTo(200);
        assertThat(plain.headers().firstValue("Content-Encoding")).isEmpty();
        assertThat(plain.body().length).as("fixture must exceed the 1 KB floor").isGreaterThan(1024);

        HttpResponse<byte[]> gzipped = send(true);
        assertThat(gzipped.statusCode()).isEqualTo(200);
        assertThat(gzipped.headers().firstValue("Content-Encoding")).contains("gzip");
        assertThat(gzipped.body().length).isLessThan(plain.body().length);
    }

    private HttpResponse<byte[]> send(boolean acceptGzip) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + env.getProperty("local.server.port") + "/api/public/materials/calc"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(TILE_REQUEST));
        if (acceptGzip) {
            req.header("Accept-Encoding", "gzip");
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
