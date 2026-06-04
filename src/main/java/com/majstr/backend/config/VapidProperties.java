package com.majstr.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Web Push / VAPID configuration. The keypair comes from the environment only
 * ({@code VAPID_PUBLIC_KEY} / {@code VAPID_PRIVATE_KEY}); when either is blank
 * (e.g. local dev without keys) the push service logs and skips sending instead
 * of failing — mirroring the email transport.
 *
 * <p>Keys are base64url-encoded as produced by any standard web-push key
 * generator. {@code subject} is a {@code mailto:} or {@code https:} URL that
 * identifies the sender to push services (RFC 8292).
 */
@ConfigurationProperties(prefix = "app.push.vapid")
public record VapidProperties(
        String publicKey,
        String privateKey,
        String subject
) {
    public boolean isConfigured() {
        return publicKey != null && !publicKey.isBlank()
                && privateKey != null && !privateKey.isBlank();
    }
}
