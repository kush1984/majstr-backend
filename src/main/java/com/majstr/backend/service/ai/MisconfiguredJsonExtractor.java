package com.majstr.backend.service.ai;

import com.majstr.backend.exception.AiExtractionException;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Stands in for a provider that was asked for but cannot be used — a name we do not recognise, or
 * one selected without its key.
 *
 * <p>It exists because the two obvious alternatives are both wrong. <b>Refusing to boot</b> takes
 * down estimates, PDFs, the client portal and billing over a setting none of them use: a master who
 * cannot open his own estimate because a recognition key is missing is worse off than one whose
 * import says "not available". <b>Falling back to another provider</b> is worse still — a
 * comparison run would silently be attributed to the wrong model, which is the one outcome that
 * makes the numbers lie.</p>
 *
 * <p>So the app starts, everything unrelated works, and recognition alone is off. The reason is
 * logged once at boot and again on every attempt, naming the exact setting to fix — which was the
 * real point of failing loudly: not the crash, but that nobody could connect "unavailable" to a
 * config change.</p>
 */
@Slf4j
public class MisconfiguredJsonExtractor implements JsonExtractor {

    private final String reason;

    public MisconfiguredJsonExtractor(String reason) {
        this.reason = reason;
        log.error("AI recognition is DISABLED: {}. Everything else runs normally; "
                + "imports and plan recognition will report \"unavailable\" until this is fixed.",
                reason);
    }

    @Override
    public String requestJson(List<AiInput> input, String systemPrompt, Map<String, Object> schema) {
        // The master sees the ordinary "unavailable" message — a config detail is not his to act on
        // — while the log says exactly what to change.
        log.error("Recognition attempted with no usable provider: {}", reason);
        throw new AiExtractionException("error.ai.unavailable");
    }

    @Override
    public String providerName() {
        return "unconfigured (" + reason + ")";
    }
}
