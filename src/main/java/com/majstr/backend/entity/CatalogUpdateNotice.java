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

import java.time.Instant;
import java.util.UUID;

/**
 * "We changed your catalog" — one pending notice per master, written by a catalog migration and
 * shown once when the app next opens.
 *
 * <p>It exists because V83 is the first thing in this project that rewrites a master's own price
 * list without them asking. That is defensible — a tiler cannot be expected to click a button
 * for positions they never knew existed — but doing it silently is not: their catalog is the
 * thing they quote from, and finding it changed with no explanation reads as data loss.</p>
 *
 * <p>Deliberately not a general notification system. It carries two numbers because that is
 * exactly what the migration knows and what the master needs to hear.</p>
 */
@Entity
@Table(name = "catalog_update_notices")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class CatalogUpdateNotice {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "positions_added", nullable = false)
    private int positionsAdded;

    @Column(name = "positions_removed", nullable = false)
    private int positionsRemoved;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Null while the master has not seen it. Set once, never unset — the row is the receipt. */
    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
