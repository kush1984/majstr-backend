package com.majstr.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * SHA-256 of a bearer token, base64url-encoded — the at-rest form for every token we
 * hand out and later look up (refresh, email verification, password reset).
 *
 * <p><b>Why hash at all.</b> These are bearer credentials: whoever holds the raw value
 * IS the user for that operation. Stored raw, anyone with read access to a DB dump or a
 * backup gets live, unexpired reset links and can take over accounts — without ever
 * touching the running system. Hashed, a dump yields nothing usable: the lookup re-hashes
 * the incoming value and compares digests, so the raw token exists only in the email we
 * sent and in the request that spends it.</p>
 *
 * <p>No salt and no work factor on purpose — unlike a password, the input is 32-48 bytes
 * of {@link java.security.SecureRandom} output, so there is nothing to brute-force or
 * rainbow-table, and the lookup must stay a single indexed equality match.</p>
 */
public final class TokenHash {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private TokenHash() {}

    public static String of(String rawToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return ENCODER.encodeToString(md.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
