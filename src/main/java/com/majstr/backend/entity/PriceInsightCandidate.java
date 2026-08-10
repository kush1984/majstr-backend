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
 * A weekly-refreshed snapshot row: one position the community-price job found worth an admin's
 * attention. {@code kind} distinguishes a price that drifted from an existing default
 * ({@link CatalogInsightKind#PRICE_DRIFT}, {@code catalogTemplateId} set) from a position no
 * default covers at all ({@link CatalogInsightKind#NEW_POSITION}, sourced this time from
 * estimate lines rather than master catalogs — {@code catalogTemplateId} null).
 *
 * <p>Snapshot, not computed per-request: the aggregation reads every non-REJECTED WORK line in
 * the database, so the admin screen reads this table instead of re-running it on every open.
 * {@code PriceInsightService.weeklyRefresh()} is the only writer.
 */
@Entity
@Table(name = "price_insight_candidate")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class PriceInsightCandidate {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private CatalogInsightKind kind;

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

    @Column(name = "category", length = 100)
    private String category;

    /** Only set for PRICE_DRIFT — the default this candidate proposes to update. */
    @Column(name = "catalog_template_id")
    private UUID catalogTemplateId;

    /** Only set for PRICE_DRIFT. */
    @Column(name = "current_default_price", precision = 15, scale = 2)
    private BigDecimal currentDefaultPrice;

    @Column(name = "proposed_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal proposedPrice;

    @Column(name = "masters_count", nullable = false)
    private int mastersCount;

    @Column(name = "min_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal minPrice;

    @Column(name = "max_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal maxPrice;

    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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
