package com.majstr.backend.config;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Builders for the app's outbound HTTP clients — with EXPLICIT timeouts.
 *
 * <p>{@code RestClient.create()} has none: if an upstream (Anthropic / monobank /
 * Resend) accepts the TCP connection and then stalls mid-response, the calling
 * thread blocks forever. A handful of stuck calls exhausts the Tomcat worker pool
 * and the whole API stops answering — for every user, not just the one importing.
 * The monobank client is the worst case: it runs inside the webhook transaction,
 * so a hang also pins a Hikari connection.</p>
 *
 * <p>Read timeouts are per-upstream because the workloads differ by orders of
 * magnitude: an LLM pass over a drawing legitimately takes minutes, a payment
 * invoice or a transactional email does not.</p>
 */
public final class HttpClients {

    /** TCP connect — the same everywhere; a healthy host answers in well under this. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private HttpClients() {}

    /**
     * A client with explicit connect + read timeouts.
     *
     * @param readTimeout how long to wait for the response body — size it to the
     *                    upstream's real work, not to "generously large"
     */
    public static RestClient withTimeouts(Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    /** LLM calls (vision over a drawing/PDF page) — minutes are normal, forever is not. */
    public static RestClient forLlm() {
        return withTimeouts(Duration.ofSeconds(120));
    }

    /** Payment API — called inside the webhook transaction, so it must fail fast. */
    public static RestClient forPayments() {
        return withTimeouts(Duration.ofSeconds(10));
    }

    /** Transactional email — @Async and fail-soft, so a short ceiling costs nothing. */
    public static RestClient forEmail() {
        return withTimeouts(Duration.ofSeconds(10));
    }
}
