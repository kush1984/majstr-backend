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
import java.util.UUID;

/**
 * Global, read-only library of starter catalog items grouped by trade.
 * Copied into a user's own {@link CatalogItem} library on registration
 * (and on demand via the {@code reset-from-template} endpoint).
 */
@Entity
@Table(name = "catalog_templates")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class CatalogTemplate {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "trade", nullable = false, length = 50)
    private Trade trade;

    /** Suggested grouping copied into the user's CatalogItem; null = "Без категорії". */
    @Column(name = "category", length = 100)
    private String category;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ItemType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false, length = 20)
    private Unit unit;

    /** 0 = the master sets their own price (the CSV had no market hint for this item). */
    @Column(name = "suggested_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal suggestedPrice;

    /**
     * Optional sentence explaining what the position guarantees, for the positions whose name
     * cannot carry it (V116, the Q3 / Q3+ / Q4 drywall finishing levels: same name shape, the
     * difference is which paints may go on top and how many control points). Copied by value
     * into {@link CatalogItem} like every other field — the master is the one who has to explain
     * it to the client.
     */
    @Column(name = "description", length = 500)
    private String description;

    /** Catalog version this template first appeared in. Lets a master pull only
     *  defaults newer than they last synced — see {@code CatalogTemplateService}. */
    @Column(name = "added_in_version", nullable = false)
    private int addedInVersion;

    /**
     * The order the library itself wants these positions read in (V118) — trade, then the phase of
     * the job, then the name. Global across trades, so a copy made for a master running six of them
     * clusters by trade instead of interleaving.
     *
     * <p>This is what a master's own {@code catalog_items.sort_order} is seeded from. Before V118
     * there was no order here at all, so every row copied after V87's one-off alphabetical backfill
     * landed on the DEFAULT 0 and the categories holding them floated to the top of his page.</p>
     */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
