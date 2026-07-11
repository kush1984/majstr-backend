package com.majstr.backend.exception;

/**
 * The LLM extraction couldn't run or produce a usable result — the Anthropic key
 * is not configured (dev), or the API call / response parsing failed. The message
 * is a bundle key resolved by the advice (→ 503, code {@code AI_UNAVAILABLE}), so
 * the master sees a friendly "try again or enter manually" instead of a 500. The
 * import is synchronous, so this is surfaced to the caller (not logged-and-skipped
 * like the fire-and-forget email/push integrations).
 */
public class AiExtractionException extends RuntimeException {
    public AiExtractionException(String messageKey) {
        super(messageKey);
    }

    public AiExtractionException(String messageKey, Throwable cause) {
        super(messageKey, cause);
    }
}
