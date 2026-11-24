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
 * The median of one position in one oblast (V222, level 1 of plan-regional-prices.md): 3+ masters
 * of that oblast after the trim, the same rule as the national median. Rebuilt weekly by
 * {@code RegionalPriceService}; nothing a master sees reads it yet.
 */
@Entity
@Table(name = "regional_price")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class RegionalPrice {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "region", nullable = false, length = 30)
    private Region region;

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

    @Column(name = "masters_count", nullable = false)
    private int mastersCount;

    @Column(name = "min_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal minPrice;

    @Column(name = "max_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal maxPrice;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
