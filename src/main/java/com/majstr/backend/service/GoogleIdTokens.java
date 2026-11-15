package com.majstr.backend.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Key;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Checks a Google «Sign in with Google» ID token: Google's signature (keys fetched from Google and cached),
 * our client id as the audience, Google as the issuer, not expired, and a Google-verified email.
 *
 * <p>No storage and no transaction here — the key fetch is the one network call, made before any
 * database work (review B-121).</p>
 */
@Slf4j
@Component
public class GoogleIdTokens {

    static final String CERTS = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");
    private static final Duration KEYS_TTL = Duration.ofHours(1);
    /** An unknown key id refetches at most this often — Google rotates rarely, a forger asks often. */
    private static final Duration REFETCH_FLOOR = Duration.ofMinutes(1);

    /** What a checked token proves. */
    public record Identity(String sub, String email, String name) {}

    private final String clientId;
    private final Supplier<String> fetchKeys;
    private final Clock clock;
    private volatile Map<String, Key> keys = Map.of();
    private volatile Instant fetchedAt = Instant.EPOCH;

    @Autowired
    public GoogleIdTokens(@Value("${app.auth.google.client-id:}") String clientId) {
        this(clientId, GoogleIdTokens::fetchFromGoogle, Clock.systemUTC());
    }

    GoogleIdTokens(String clientId, Supplier<String> fetchKeys, Clock clock) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.fetchKeys = fetchKeys;
        this.clock = clock;
    }

    /** False until GOOGLE_CLIENT_ID is set — the endpoint then answers 404. */
    public boolean enabled() {
        return !clientId.isEmpty();
    }

    public Identity verify(String credential) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .keyLocator(new LocatorAdapter<Key>() {
                        @Override
                        protected Key locate(JwsHeader header) {
                            return keyFor(header.getKeyId());
                        }
                    })
                    .clock(() -> java.util.Date.from(clock.instant()))
                    .requireAudience(clientId)
                    .build()
                    .parseSignedClaims(credential)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new BadCredentialsException("Invalid Google token");
        }
        if (!ISSUERS.contains(claims.getIssuer())) {
            throw new BadCredentialsException("Invalid Google token issuer");
        }
        String email = claims.get("email", String.class);
        if (email == null || email.isBlank() || !Boolean.TRUE.equals(claims.get("email_verified", Boolean.class))) {
            throw new BadCredentialsException("Google account email is not verified");
        }
        String sub = claims.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new BadCredentialsException("Invalid Google token subject");
        }
        return new Identity(sub, email.trim().toLowerCase(java.util.Locale.ROOT), claims.get("name", String.class));
    }

    private Key keyFor(String kid) {
        if (kid == null) {
            throw new BadCredentialsException("Google token without a key id");
        }
        Instant now = clock.instant();
        Key key = keys.get(kid);
        boolean stale = fetchedAt.plus(KEYS_TTL).isBefore(now);
        boolean mayRefetch = fetchedAt.plus(REFETCH_FLOOR).isBefore(now);
        if (stale || (key == null && mayRefetch)) {
            refresh(now);
            key = keys.get(kid);
        }
        if (key == null) {
            throw new BadCredentialsException("Unknown Google key");
        }
        return key;
    }

    private synchronized void refresh(Instant now) {
        try {
            JwkSet set = Jwks.setParser().build().parse(fetchKeys.get());
            Map<String, Key> next = new HashMap<>();
            for (Jwk<?> jwk : set.getKeys()) {
                if (jwk.getId() != null) {
                    next.put(jwk.getId(), jwk.toKey());
                }
            }
            keys = Map.copyOf(next);
            fetchedAt = now;
        } catch (RuntimeException e) {
            // Keep the old keys: a Google outage must not log out a key we already hold.
            log.warn("Could not refresh Google signing keys: {}", e.getMessage());
            fetchedAt = now.minus(KEYS_TTL).plus(REFETCH_FLOOR);
        }
    }

    private static String fetchFromGoogle() {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(CERTS)).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Google keys answered " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("Google keys unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted fetching Google keys", e);
        }
    }
}
