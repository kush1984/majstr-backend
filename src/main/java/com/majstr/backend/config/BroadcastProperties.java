package com.majstr.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Admin broadcasts (V180). {@code sendIntervalMs} paces the letters under Resend's per-second API
 * limit; tests set it to 0.
 */
@ConfigurationProperties(prefix = "app.broadcast")
public record BroadcastProperties(
        String fromName,
        String replyTo,
        long sendIntervalMs
) {}
