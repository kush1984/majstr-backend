package com.majstr.backend.dto;

import java.time.Instant;

/**
 * «Подивитися, як побачить клієнт»: the client's page for one estimate, opened in the master's own
 * browser. A preview pass, not a share link — it expires at {@code expiresAt} and never reaches
 * {@code estimate_share_links}.
 */
public record ClientPreviewResponse(String url, Instant expiresAt) {}
