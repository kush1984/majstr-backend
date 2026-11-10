package com.majstr.backend.service;

import com.majstr.backend.config.JwtProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * «Подивитися, як побачить клієнт» (iteration-first-experience): a ten-minute pass that lets the
 * master's own browser open the client's page — for one estimate ({@code ?v=}), or for the object's
 * portal with the estimates he ticked in the share sheet ({@code ?vp=}).
 *
 * <p>The portal lives on the backend's domain and the master's JWT lives in the PWA, so the page
 * cannot simply carry his session. Neither can it borrow a share link: minting one is a footprint
 * (and a link that could leave), and the object portal's visibility flags are the PUBLISHED set — a
 * preview of a selection he has not published yet must not write them. So the pass is
 * <b>stateless</b>: what to show and an expiry, signed with a key DERIVED from the JWT secret under
 * its own label, so it can never be read as an access token nor an access token as one of these.
 * Nothing is stored: no row to sweep, no column on the share tables, and a leaked pass is dead ten
 * minutes later. Not single-use on purpose: the page fetches the estimate, then its photos, and
 * reloads on rotate — a one-shot pass would need a table and break the very look it exists for.</p>
 *
 * <p>Body: a kind byte, the expiry, then the ids — {@code E} + the estimate, or {@code P} + the
 * object + the ticked estimates (at most {@link #MAX_PORTAL_ESTIMATES}).</p>
 */
@Component
public class PortalPreviewTokens {

    static final Duration TTL = Duration.ofMinutes(10);
    /** More than any object holds; it only bounds the URL a crafted request could ask for. */
    public static final int MAX_PORTAL_ESTIMATES = 100;
    private static final String LABEL = "majstr/portal-preview/v1";
    private static final byte ESTIMATE = 'E';
    private static final byte PORTAL = 'P';
    private static final int UUID_BYTES = 16;
    private static final int HEAD_BYTES = 1 + Long.BYTES;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /** The object and the estimates a portal pass shows, in the order they were ticked. */
    public record PortalPass(UUID projectId, List<UUID> estimateIds) {}

    private final SecretKeySpec key;
    private final Clock clock;

    @Autowired
    public PortalPreviewTokens(JwtProperties jwt) {
        this(jwt, Clock.systemUTC());
    }

    PortalPreviewTokens(JwtProperties jwt, Clock clock) {
        this.key = new SecretKeySpec(hmac(new SecretKeySpec(
                jwt.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"),
                LABEL.getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
        this.clock = clock;
    }

    public String issue(UUID estimateId) {
        return seal(ESTIMATE, List.of(estimateId));
    }

    public String issuePortal(UUID projectId, List<UUID> estimateIds) {
        if (estimateIds.size() > MAX_PORTAL_ESTIMATES) {
            throw new IllegalArgumentException("too many estimates for one preview");
        }
        List<UUID> ids = new ArrayList<>(estimateIds.size() + 1);
        ids.add(projectId);
        ids.addAll(estimateIds);
        return seal(PORTAL, ids);
    }

    public Instant expiresAt() {
        return Instant.now(clock).plus(TTL);
    }

    /** The estimate a live single-estimate pass names; empty for anything forged, expired or a portal pass. */
    public Optional<UUID> estimateOf(String token) {
        return open(token, ESTIMATE).filter(ids -> ids.size() == 1).map(ids -> ids.get(0));
    }

    /** The object and estimates a live portal pass names; empty for anything else. */
    public Optional<PortalPass> portalOf(String token) {
        return open(token, PORTAL).filter(ids -> !ids.isEmpty())
                .map(ids -> new PortalPass(ids.get(0), List.copyOf(ids.subList(1, ids.size()))));
    }

    private String seal(byte kind, List<UUID> ids) {
        ByteBuffer body = ByteBuffer.allocate(HEAD_BYTES + ids.size() * UUID_BYTES)
                .put(kind)
                .putLong(Instant.now(clock).plus(TTL).getEpochSecond());
        ids.forEach(id -> body.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()));
        String head = ENCODER.encodeToString(body.array());
        return head + "." + ENCODER.encodeToString(hmac(key, head.getBytes(StandardCharsets.US_ASCII)));
    }

    private Optional<List<UUID>> open(String token, byte kind) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return Optional.empty();
        }
        String head = token.substring(0, dot);
        try {
            byte[] mac = DECODER.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(mac, hmac(key, head.getBytes(StandardCharsets.US_ASCII)))) {
                return Optional.empty();
            }
            byte[] body = DECODER.decode(head);
            if (body.length < HEAD_BYTES || (body.length - HEAD_BYTES) % UUID_BYTES != 0) {
                return Optional.empty();
            }
            ByteBuffer buf = ByteBuffer.wrap(body);
            if (buf.get() != kind || Instant.now(clock).getEpochSecond() >= buf.getLong()) {
                return Optional.empty();
            }
            List<UUID> ids = new ArrayList<>();
            while (buf.hasRemaining()) {
                ids.add(new UUID(buf.getLong(), buf.getLong()));
            }
            return Optional.of(ids);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static byte[] hmac(SecretKeySpec key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
