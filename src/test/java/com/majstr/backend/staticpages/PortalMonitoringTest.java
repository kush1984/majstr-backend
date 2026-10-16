package com.majstr.backend.staticpages;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The portal reports its failures to Sentry without an SDK and without leaking: the link's token is
 * scrubbed from every URL and nothing the client typed is read by the reporter. Read as TEXT, like
 * the other static-page guards — there is no server render that would show it.
 */
class PortalMonitoringTest {

    private static String reporter() throws IOException {
        String page = Files.readString(Path.of("src/main/resources/static/portal/index.html"), StandardCharsets.UTF_8);
        int start = page.indexOf("const monitoring = (function () {");
        int end = page.indexOf("})();", start);
        assertThat(start).as("the reporter block").isPositive();
        return page.substring(start, end);
    }

    @Test
    void theTokenIsScrubbedAndNothingTypedIsRead() throws IOException {
        String reporter = reporter();
        assertThat(reporter)
                .contains("(?:p|e|a|t|m)=")
                .contains("portal|estimates|economy|act|messages?")
                .contains("request: { url: scrub(")
                .as("Sentry infers the sender's IP for a 'javascript' event")
                .contains("platform: 'other'").contains("user: { ip_address: null }")
                .doesNotContain("signName").doesNotContain("signPhone")
                .doesNotContain("actSignName").doesNotContain("actSignPhone")
                .doesNotContain("<script src");
        assertThat(reporter).as("asks only once something went wrong").contains("/api/public/monitoring");
    }

    @Test
    void aFailedSignatureOrLoadIsReported() throws IOException {
        String page = Files.readString(Path.of("src/main/resources/static/portal/index.html"), StandardCharsets.UTF_8);
        assertThat(page)
                .contains("monitoring.failed('estimate-sign', resp.status)")
                .contains("monitoring.unreachable('estimate-sign')")
                .contains("monitoring.failed('act-sign', resp.status)")
                .contains("monitoring.unreachable('act-sign')")
                .contains("monitoring.failed('load', resp.status)")
                .contains("monitoring.unreachable('load')");
    }
}
