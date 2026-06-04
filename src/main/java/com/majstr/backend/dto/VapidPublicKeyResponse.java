package com.majstr.backend.dto;

/**
 * The server's VAPID public key, needed by the browser to create a push
 * subscription. {@code null} when push is not configured on the server.
 */
public record VapidPublicKeyResponse(String publicKey) {}
