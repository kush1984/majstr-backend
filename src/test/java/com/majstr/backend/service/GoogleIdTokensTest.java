package com.majstr.backend.service;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A Google ID token is believed only with Google's signature, our audience, Google's issuer and a verified email. */
class GoogleIdTokensTest {

    private static final String CLIENT = "123-abc.apps.googleusercontent.com";
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final KeyPair GOOGLE = rsa();
    private static final KeyPair FORGER = rsa();

    private final AtomicInteger fetches = new AtomicInteger();
    private final GoogleIdTokens tokens = new GoogleIdTokens(CLIENT, () -> {
        fetches.incrementAndGet();
        return jwks("k1", (RSAPublicKey) GOOGLE.getPublic());
    }, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aGoogleSignedTokenForUsWithAVerifiedEmailIsBelieved() {
        GoogleIdTokens.Identity id = tokens.verify(token(GOOGLE, "k1", CLIENT, "https://accounts.google.com",
                Map.of("email", "Master@Gmail.com", "email_verified", true, "name", "Петро")));

        assertThat(id.sub()).isEqualTo("google-sub-1");
        assertThat(id.email()).isEqualTo("master@gmail.com");
        assertThat(id.name()).isEqualTo("Петро");
    }

    @Test
    void anotherAppsTokenAForgedOneAnUnverifiedEmailOrAnotherIssuerAreRefused() {
        Map<String, Object> ok = Map.of("email", "m@gmail.com", "email_verified", true);
        assertThatThrownBy(() -> tokens.verify(token(GOOGLE, "k1", "someone-else", "accounts.google.com", ok)))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> tokens.verify(token(FORGER, "k1", CLIENT, "accounts.google.com", ok)))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> tokens.verify(token(GOOGLE, "k1", CLIENT, "accounts.google.com",
                Map.of("email", "m@gmail.com", "email_verified", false))))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> tokens.verify(token(GOOGLE, "k1", CLIENT, "evil.example", ok)))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> tokens.verify("not-a-token")).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void anUnknownKeyIdDoesNotHammerGoogle() {
        Map<String, Object> ok = Map.of("email", "m@gmail.com", "email_verified", true);
        tokens.verify(token(GOOGLE, "k1", CLIENT, "accounts.google.com", ok));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> tokens.verify(token(GOOGLE, "k-unknown", CLIENT, "accounts.google.com", ok)))
                    .isInstanceOf(BadCredentialsException.class);
        }
        assertThat(fetches.get()).as("one fetch, then the floor holds").isEqualTo(1);
    }

    @Test
    void offUntilAClientIdIsSet() {
        assertThat(new GoogleIdTokens(" ", () -> "{}", Clock.systemUTC()).enabled()).isFalse();
        assertThat(tokens.enabled()).isTrue();
    }

    private static String token(KeyPair signer, String kid, String audience, String issuer, Map<String, Object> claims) {
        return Jwts.builder()
                .header().keyId(kid).and()
                .subject("google-sub-1")
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(Date.from(NOW.minusSeconds(60)))
                .expiration(Date.from(NOW.plusSeconds(3600)))
                .claims(claims)
                .signWith(signer.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static String jwks(String kid, RSAPublicKey key) {
        return "{\"keys\":[{\"kty\":\"RSA\",\"alg\":\"RS256\",\"use\":\"sig\",\"kid\":\"" + kid
                + "\",\"n\":\"" + b64(key.getModulus()) + "\",\"e\":\"" + b64(key.getPublicExponent()) + "\"}]}";
    }

    private static String b64(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
