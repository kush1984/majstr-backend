package com.majstr.backend.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

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

    private AiHttp() {}

    /** Transient = worth retrying: 429 (rate limit), or any 5xx (incl. Anthropic's 529 "Overloaded"). */
    public static boolean isTransient(int status) {
        return status == 429 || status >= 500;
    }

    public static Map<String, Object> withRetry(String provider, Supplier<Map<String, Object>> call) {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (RestClientResponseException e) { // carries the HTTP status
                if (attempt >= MAX_ATTEMPTS || !isTransient(e.getStatusCode().value())) {
                    throw e;
                }
                backoff(provider, attempt, e);
            } catch (ResourceAccessException e) { // connection reset / read timeout
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                backoff(provider, attempt, e);
            }
        }
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
