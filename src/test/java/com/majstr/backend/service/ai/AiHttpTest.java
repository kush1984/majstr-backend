package com.majstr.backend.service.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The retry rule, in the one place both providers read it from.
 *
 * <p>These assertions used to live in the estimate extractor, back when that class was also the
 * Anthropic client and each provider carried its own copy of the policy. Two copies of a rule are
 * two rules waiting to disagree.</p>
 */
class AiHttpTest {

    private final AtomicInteger calls = new AtomicInteger();

    private Map<String, Object> failingWith(RuntimeException failure) {
        calls.incrementAndGet();
        throw failure;
    }

    @Test
    void retriesTransientStatusesButNotPermanentOnes() {
        // Transient — a quick retry is worth it (the master is waiting on a synchronous import).
        assertThat(AiHttp.isTransient(429)).isTrue(); // rate limit
        assertThat(AiHttp.isTransient(500)).isTrue();
        assertThat(AiHttp.isTransient(503)).isTrue();
        assertThat(AiHttp.isTransient(529)).isTrue(); // Anthropic "Overloaded"
        // Permanent — retrying can't help; fail fast to the manual-entry fallback.
        assertThat(AiHttp.isTransient(400)).isFalse(); // bad request
        assertThat(AiHttp.isTransient(401)).isFalse(); // bad key
        assertThat(AiHttp.isTransient(413)).isFalse(); // payload too large
        assertThat(AiHttp.isTransient(404)).isFalse();
    }

    @Test
    void aReadTimeoutIsNotRetried_theModelMayAlreadyBeBillingForIt() {
        ResourceAccessException timeout =
                new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out"));

        assertThatThrownBy(() -> AiHttp.withRetry("test", () -> failingWith(timeout))).isSameAs(timeout);

        assertThat(calls).hasValue(1);
    }

    @Test
    void aResetMidResponseIsNotRetried() {
        ResourceAccessException reset = new ResourceAccessException("I/O error", new IOException("Connection reset"));

        assertThatThrownBy(() -> AiHttp.withRetry("test", () -> failingWith(reset))).isSameAs(reset);

        assertThat(calls).hasValue(1);
    }

    @Test
    void aFailureToConnectIsRetried_nothingReachedTheModel() {
        ResourceAccessException refused = new ResourceAccessException("I/O error", new ConnectException("refused"));

        assertThatThrownBy(() -> AiHttp.withRetry("test", () -> failingWith(refused))).isSameAs(refused);

        assertThat(calls).hasValue(AiHttp.MAX_ATTEMPTS);
    }

    @Test
    void aConnectTimeoutCountsAsNotHavingConnected() {
        assertThat(AiHttp.neverReachedTheServer(
                new ResourceAccessException("x", new SocketTimeoutException("Connect timed out")))).isTrue();
        assertThat(AiHttp.neverReachedTheServer(
                new ResourceAccessException("x", new SocketTimeoutException("Read timed out")))).isFalse();
    }

    @Test
    void aRateLimitOrAnOverloadIsStillRetried() {
        Map<String, Object> answer = Map.of("ok", true);

        Map<String, Object> result = AiHttp.withRetry("test", () -> {
            if (calls.incrementAndGet() == 1) {
                throw new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
            }
            return answer;
        });

        assertThat(result).isSameAs(answer);
        assertThat(calls).hasValue(2);
    }

    @Test
    void aPermanentRefusalIsNotRetried() {
        HttpClientErrorException badRequest = new HttpClientErrorException(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> AiHttp.withRetry("test", () -> failingWith(badRequest))).isSameAs(badRequest);

        assertThat(calls).hasValue(1);
    }
}
