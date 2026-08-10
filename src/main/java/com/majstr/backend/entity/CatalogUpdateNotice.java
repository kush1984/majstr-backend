package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * "We changed your catalog" — a queue of pending notices for a master, shown on next app open.
 *
 * <p>It exists because V83 is the first thing in this project that rewrites a master's own price
 * list without them asking. That is defensible — a tiler cannot be expected to click a button
 * for positions they never knew existed — but doing it silently is not: their catalog is the
 * thing they quote from, and finding it changed with no explanation reads as data loss.</p>
 *
 * <p>Deliberately not a general notification system — {@link #kind} names exactly the two shapes
 * this carries, nothing else. It started out ({@code kind = COUNT}) as one row per master,
 * written by a migration, carrying the two numbers a migration knows: positions added/removed.
 * The community price-drift feature (V94) reprices masters' positions one at a time, potentially
 * several a week, so it needed real multiple-rows-per-master ({@code kind = PRICE_DRIFT},
 * {@link #positionName}/{@link #oldPrice}/{@link #newPrice}) rather than reusing one slot — a
 * single-slot design would have silently dropped all but the most recent reprice. Each row is
 * dismissed independently.</p>
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

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    @Builder.Default
    private CatalogUpdateNoticeKind kind = CatalogUpdateNoticeKind.COUNT;

    /** Set only for {@code kind = COUNT}; 0 otherwise. */
    @Column(name = "positions_added", nullable = false)
    private int positionsAdded;

    /** Set only for {@code kind = COUNT}; 0 otherwise. */
    @Column(name = "positions_removed", nullable = false)
    private int positionsRemoved;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Null while the master has not seen it. Set once, never unset — the row is the receipt. */
    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    /** Set only for {@code kind = PRICE_DRIFT}; null otherwise. The three travel together — a
     *  row has either all three or none (DB CHECK enforces this, see V94). */
    @Column(name = "position_name")
    private String positionName;

    @Column(name = "old_price", precision = 15, scale = 2)
    private BigDecimal oldPrice;

    @Column(name = "new_price", precision = 15, scale = 2)
    private BigDecimal newPrice;

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
