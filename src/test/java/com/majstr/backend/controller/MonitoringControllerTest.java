package com.majstr.backend.controller;

import com.majstr.backend.config.SentryProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringControllerTest {

    private static final String DSN = "https://abc123@o4511520783859712.ingest.de.sentry.io/4511520867811408";

    @Test
    void aDsnBecomesItsEnvelopeUrl() {
        assertThat(MonitoringController.envelopeUrl(DSN)).isEqualTo(
                "https://o4511520783859712.ingest.de.sentry.io/api/4511520867811408/envelope/"
                        + "?sentry_key=abc123&sentry_version=7");
    }

    @Test
    void blankOrUnreadableMeansReportingIsOff() {
        assertThat(MonitoringController.envelopeUrl(null)).isNull();
        assertThat(MonitoringController.envelopeUrl("  ")).isNull();
        assertThat(MonitoringController.envelopeUrl("https://o1.ingest.sentry.io/123")).isNull();   // no key
        assertThat(MonitoringController.envelopeUrl("https://k@o1.ingest.sentry.io/abc")).isNull(); // no project
        assertThat(MonitoringController.envelopeUrl("not a url ::")).isNull();
    }

    @Test
    void theEndpointAnswers204WhenOffAnd200WithTheUrlWhenOn() {
        assertThat(new MonitoringController(new SentryProperties("", "prod", 0.0, ""))
                .config().getStatusCode().value()).isEqualTo(204);

        var on = new MonitoringController(new SentryProperties("", "prod", 0.0, DSN)).config();
        assertThat(on.getStatusCode().value()).isEqualTo(200);
        assertThat(on.getBody()).isNotNull();
        assertThat(on.getBody().endpoint()).contains("/api/4511520867811408/envelope/");
        assertThat(on.getBody().environment()).isEqualTo("prod");
    }
}
