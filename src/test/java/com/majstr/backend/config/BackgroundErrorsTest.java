package com.majstr.backend.config;

import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Background failures reach Sentry tagged by task. Sentry is initialised with a fake DSN and a
 * {@code beforeSend} that records the event and drops it, so nothing leaves the machine.
 */
class BackgroundErrorsTest {

    private final List<SentryEvent> sent = new CopyOnWriteArrayList<>();

    @BeforeEach
    void initSentry() {
        Sentry.init(options -> {
            options.setDsn("https://key@o0.ingest.sentry.io/0");
            options.setBeforeSend((event, hint) -> {
                sent.add(event);
                return null;
            });
        });
    }

    @AfterEach
    void closeSentry() {
        Sentry.close();
    }

    @Test
    void aFailSoftFailureIsReportedWithItsTask() {
        BackgroundErrors.capture("email", new IllegalStateException("resend down"));

        assertThat(sent).singleElement().satisfies(e -> {
            assertThat(e.getTag("background")).isEqualTo("email");
            assertThat(e.getLevel()).isEqualTo(SentryLevel.ERROR);
            assertThat(e.getThrowable()).hasMessage("resend down");
        });
    }

    @Test
    void aWarningStaysAWarning() {
        BackgroundErrors.capture("retention:delete-file", new RuntimeException("gone"), SentryLevel.WARNING);

        assertThat(sent).singleElement().satisfies(e -> assertThat(e.getLevel()).isEqualTo(SentryLevel.WARNING));
    }

    @Test
    void whatEscapesAScheduledJobIsReported() {
        BackgroundErrorConfig.SCHEDULED.handleError(new IllegalStateException("nightly job"));

        assertThat(sent).extracting(e -> e.getTag("background")).containsExactly("scheduled");
    }
}
