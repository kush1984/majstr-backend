package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * The object-level portal link. One usable link per project at a time
 * (minted idempotently); which estimates the portal shows is controlled by
 * {@link Estimate#isPortalVisible()}, not by the link itself.
 */
@Entity
@Table(name = "project_share_links")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ProjectShareLink {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    /** Raw token, same trade-off as {@link EstimateShareLink#getToken()} (documented in CLAUDE.md). */
    @Column(name = "token", nullable = false, unique = true, length = 128)
    private String token;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    /** What this link opens — see {@link ShareLinkKind}. A privacy boundary, so never inferred. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    @Builder.Default
    private ShareLinkKind kind = ShareLinkKind.PORTAL;

    /** Whether the object-level payments card shows on the portal. Off by default — the master
     *  opts in explicitly (V93), mirroring {@code Estimate.portalVisible}'s per-estimate cousin
     *  but as ONE flag for the whole object (payments aren't tied to any single estimate). */
    @Column(name = "payments_visible", nullable = false)
    private boolean paymentsVisible;

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
        return !revoked && (expiresAt == null || expiresAt.isAfter(now));
    }
}
