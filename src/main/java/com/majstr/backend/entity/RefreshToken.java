package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class RefreshToken {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    /**
     * When this token was exchanged for a new one. {@code null} means it was never rotated —
     * either still live, or revoked by an explicit logout.
     *
     * <p>The distinction is the whole point: a token rotated seconds ago may be replayed once
     * more (the client never received the replacement), but a token the user logged out of
     * must die immediately. Only rotation stamps this, so only rotation is forgiving.
     */
    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isUsable(Instant now) {
        return !revoked && expiresAt.isAfter(now);
    }

    /**
     * True when this token is revoked, but only because it was rotated within {@code grace} of
     * {@code now} — i.e. the holder plausibly never received the replacement.
     *
     * <p>Expiry is still enforced by the caller: a genuinely expired token gets no grace, and
     * neither does one revoked by logout ({@code rotatedAt} stays null there).
     */
    public boolean isWithinRotationGrace(Instant now, Duration grace) {
        return revoked
                && rotatedAt != null
                && rotatedAt.isAfter(now.minus(grace));
    }
}
