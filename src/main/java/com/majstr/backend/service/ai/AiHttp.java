package com.majstr.backend.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The retry rules every LLM provider needs, in one place.
 *
 * <p>Recognition is synchronous — the master is watching a spinner — so retries are few and quick:
 * enough to ride out a rate limit or an overloaded upstream, not enough to make waiting worse than
 * failing. A permanent 4xx (bad key, bad request, payload too large) is never retried: nothing about
 * sending it again would change the answer.</p>
 */
@Slf4j
public final class AiHttp {

    static final int MAX_ATTEMPTS = 3;
    static final long BACKOFF_BASE_MS = 400L;
    /** One more attempt may itself take {@code HttpClients.forLlm}'s 170 s, and the PWA gives up at 180 s. */
    private static final Duration RETRY_WINDOW = Duration.ofSeconds(9);

    private AiHttp() {}

    /** Transient = worth retrying: 429 (rate limit), or any 5xx (incl. Anthropic's 529 "Overloaded"). */
    public static boolean isTransient(int status) {
        return status == 429 || status >= 500;
    }

    public static Map<String, Object> withRetry(String provider, Supplier<Map<String, Object>> call) {
        long startedAt = System.nanoTime();
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (RestClientResponseException e) { // carries the HTTP status
                if (attempt >= MAX_ATTEMPTS || !isTransient(e.getStatusCode().value()) || tooLate(startedAt)) {
                    throw e;
                }
                backoff(provider, attempt, e);
            } catch (ResourceAccessException e) {
                // Only a failure to connect. A read timeout or a reset may have reached the model, which
                // bills for it whether or not we hear back: three of those held a Tomcat thread for
                // 8.5 minutes and paid three times, long after the master had given up.
                if (attempt >= MAX_ATTEMPTS || !neverReachedTheServer(e) || tooLate(startedAt)) {
                    throw e;
                }
                backoff(provider, attempt, e);
            }
        }
    }

    private static boolean tooLate(long startedAt) {
        return System.nanoTime() - startedAt > RETRY_WINDOW.toNanos();
    }

    static boolean neverReachedTheServer(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof NoRouteToHostException
                    || t instanceof UnknownHostException) {
                return true;
            }
            if (t instanceof SocketTimeoutException
                    && String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT).contains("connect")) {
                return true;
            }
        }
        return false;
    }

    private static void backoff(String provider, int attempt, RuntimeException cause) {
        log.warn("{} call transient failure (attempt {}/{}), retrying: {}",
                provider, attempt, MAX_ATTEMPTS, cause.getMessage());
        try {
            Thread.sleep(BACKOFF_BASE_MS * attempt); // 400ms, 800ms — the master is waiting
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw cause; // give up promptly if the request thread is interrupted
        }
    }
}
