package com.majstr.backend.controller;

import com.majstr.backend.config.SentryProperties;
import com.majstr.backend.dto.MonitoringConfigResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Where a client page sends an error report. The portal is static HTML and cannot read the
 * environment, so it asks here — and only when something has already gone wrong, so an ordinary
 * visit costs no request.
 *
 * <p>The answer is the Sentry envelope URL of {@code SENTRY_BROWSER_DSN} (a DSN's key is public by
 * design: every browser SDK ships it). Blank or unreadable = 204, and the page reports nothing.</p>
 */
@Tag(name = "Public")
@RestController
@RequestMapping("/api/public/monitoring")
@RequiredArgsConstructor
public class MonitoringController {

    private final SentryProperties sentry;

    @Operation(summary = "Error-report endpoint for the client pages, or 204 when reporting is off")
    @GetMapping
    public ResponseEntity<MonitoringConfigResponse> config() {
        String endpoint = envelopeUrl(sentry.browserDsn());
        if (endpoint == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(new MonitoringConfigResponse(endpoint, sentry.environment()));
    }

    /** {@code https://KEY@HOST/PROJECT} to Sentry's envelope endpoint; null when there is no usable DSN. */
    static String envelopeUrl(String dsn) {
        if (dsn == null || dsn.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(dsn.trim());
            String key = uri.getUserInfo();
            String project = uri.getPath() == null ? "" : uri.getPath().replaceAll("^/+|/+$", "");
            if (key == null || key.isBlank() || uri.getHost() == null || !project.matches("\\d+")) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                    + "/api/" + project + "/envelope/?sentry_key=" + key + "&sentry_version=7";
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
