package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A browser Web Push subscription as produced by
 * {@code PushManager.subscribe()} on the client. {@code keys.p256dh} and
 * {@code keys.auth} are the RFC 8291 encryption keys.
 */
public record PushSubscribeRequest(
        @NotBlank @Size(max = 2048) String endpoint,
        @NotBlank @Size(max = 255) String p256dh,
        @NotBlank @Size(max = 255) String auth,
        @Size(max = 512) String userAgent
) {}
