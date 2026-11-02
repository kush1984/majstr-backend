package com.majstr.backend.config;

import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SentryInitializerTest {

    @Test
    void anEventLeavesWithNoPublicTokenAnywhereInIt() {
        SentryEvent event = new SentryEvent();
        event.setTransaction("GET /api/public/portal/SECRET/photos");
        event.setTag("endpoint", "POST /api/public/act/SECRET/sign");
        Message message = new Message();
        message.setFormatted("failed at /api/public/economy/SECRET");
        event.setMessage(message);
        SentryException exception = new SentryException();
        exception.setValue("No static resource /api/public/estimates/SECRET/x");
        event.setExceptions(List.of(exception));

        SentryEvent scrubbed = SentryInitializer.scrub(event);

        assertThat(scrubbed.getTransaction()).doesNotContain("SECRET");
        assertThat(scrubbed.getTag("endpoint")).isEqualTo("POST /api/public/act/***/sign");
        assertThat(scrubbed.getMessage().getFormatted()).doesNotContain("SECRET");
        assertThat(scrubbed.getExceptions().get(0).getValue()).doesNotContain("SECRET");
    }
}
