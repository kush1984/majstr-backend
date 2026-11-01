package com.majstr.backend.integration;

import com.majstr.backend.security.TrustedForwardedHeaderFilter;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production's forwarded-header setup end to end: the login limiter must not be walked around with a
 * rotating {@code Forwarded} header.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.forward-headers-strategy=framework")
class ForwardedHeaderSpoofIntegrationTest extends IntegrationTestBase {

    @Autowired Environment env;
    @Autowired ApplicationContext context;

    private final HttpClient http = HttpClient.newHttpClient();

    private int login(String email, String header, String value) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + env.getProperty("local.server.port") + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .header(header, value)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}"))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static String freshEmail() {
        return "spoof-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void aRotatingForwardedHeaderStillHitsTheLimit() throws Exception {
        String email = freshEmail();
        for (int i = 1; i <= 5; i++) {
            assertThat(login(email, "Forwarded", "for=198.51.100." + i)).isEqualTo(401);
        }
        assertThat(login(email, "Forwarded", "for=198.51.100.6")).isEqualTo(429);
    }

    @Test
    void theEdgesAddressStillSeparatesClients() throws Exception {
        String email = freshEmail();
        for (int i = 1; i <= 5; i++) {
            assertThat(login(email, "X-Forwarded-For", "203.0.113.10")).isEqualTo(401);
        }
        assertThat(login(email, "X-Forwarded-For", "203.0.113.10")).isEqualTo(429);
        assertThat(login(email, "X-Forwarded-For", "203.0.113.11")).isEqualTo(401);
    }

    @Test
    void oursIsTheOnlyForwardedHeaderFilter() {
        List<Filter> forwardedFilters = context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .map(registration -> (Filter) registration.getFilter())
                .filter(ForwardedHeaderFilter.class::isInstance)
                .toList();
        assertThat(forwardedFilters).singleElement().isInstanceOf(TrustedForwardedHeaderFilter.class);
    }
}
