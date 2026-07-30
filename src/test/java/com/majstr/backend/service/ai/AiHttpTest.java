package com.majstr.backend.service.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retry rule, in the one place both providers read it from.
 *
 * <p>These assertions used to live in the estimate extractor, back when that class was also the
 * Anthropic client and each provider carried its own copy of the policy. Two copies of a rule are
 * two rules waiting to disagree.</p>
 */
class AiHttpTest {

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
}
