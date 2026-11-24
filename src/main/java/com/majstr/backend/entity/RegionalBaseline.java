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
 * The «country» price of one position that oblast factors are measured against (V223): the median
 * of the oblasts' own medians — one vote per oblast, 3+ oblasts. Not the V94 national median (one
 * vote per master), which leans towards the oblasts where most masters are. Rebuilt weekly by
 * {@code RegionalPriceService}.
 */
@Entity
@Table(name = "regional_baseline")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class RegionalBaseline {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name_key", nullable = false, length = 512)
    private String nameKey;

    @Column(name = "sample_name", nullable = false, length = 255)
    private String sampleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 20)
    private ItemType itemType;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false, length = 20)
    private Unit unit;

    @Column(name = "median_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal medianPrice;

    /** Oblasts that voted — each with the median of its own masters. */
    @Column(name = "regions_count", nullable = false)
    private int regionsCount;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
