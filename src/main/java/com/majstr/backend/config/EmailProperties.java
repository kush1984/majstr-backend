package com.majstr.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Email / Resend configuration. The API key comes from the environment only
 * ({@code RESEND_API_KEY}); when blank (e.g. local dev without a key) the
 * email service logs and skips sending instead of failing.
 */
@ConfigurationProperties(prefix = "app.email")
public record EmailProperties(
        String resendApiKey,
        String fromAddress,
        String appUrl
) {
    public boolean isConfigured() {
        return resendApiKey != null && !resendApiKey.isBlank();
    }
}
