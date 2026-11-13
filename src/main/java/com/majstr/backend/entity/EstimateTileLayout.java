package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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

/** A tiling line's layout as the master accepted it on the phone (V203, iteration-tile-layout). */
@Entity
@Table(name = "estimate_tile_layout")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class EstimateTileLayout {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "estimate_id", nullable = false, updatable = false)
    private UUID estimateId;

    @Column(name = "estimate_item_id", nullable = false, updatable = false)
    private UUID estimateItemId;

    /** The format's two sides, short first — how the library names it (300×600). */
    @Column(name = "tile_short_mm", nullable = false)
    private int tileShortMm;

    @Column(name = "tile_long_mm", nullable = false)
    private int tileLongMm;

    /** m² of the line the layout covers; the rest of the line is bought by area. */
    @Column(name = "laid_area", nullable = false, precision = 10, scale = 3)
    private BigDecimal laidArea;

    /** Offcuts reused under the factory-edge rule. */
    @Column(name = "min_tiles", nullable = false)
    private int minTiles;

    /** Every cut from a fresh tile — what is bought, with {@link #spare} on top. */
    @Column(name = "max_tiles", nullable = false)
    private int maxTiles;

    @Column(name = "spare", nullable = false)
    private int spare;

    /** The layout's inputs as the PWA sent them (JSON), so «Перерахувати» reopens them. */
    @Column(name = "params", nullable = false)
    private String params;

    @Column(name = "measurements_declined", nullable = false)
    private boolean measurementsDeclined;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** What the calculator buys for the laid part. */
    public int tilesToBuy() {
        return maxTiles + spare;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
