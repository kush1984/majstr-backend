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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "catalog_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class CatalogItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private User owner;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Free-text grouping the contractor controls; null = "Без категорії". */
    @Column(name = "category", length = 100)
    private String category;

    /**
     * Optional sentence carried over from {@link CatalogTemplate} on the library copy (V116).
     *
     * <p>Read path only for now: {@code CatalogItemRequest} deliberately has no field for it,
     * because the PWA does not send one and a PATCH that omits a column it cannot see would null
     * the text on the master's first edit. See docs/open-questions.md.
     */
    @Column(name = "description", length = 500)
    private String description;

    /** Trade this position belongs to — copied from the template on every seed/
     *  reset/merge path, optionally set on manual create. Never null: a position
     *  with no specific trade is OTHER ("Інше"), the single catch-all (V33). Drives
     *  the catalog trade filter. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "trade", nullable = false, length = 50)
    private Trade trade = Trade.OTHER;

    /** A master-invented trade instead of a system one. When set, {@code trade} is always OTHER
     *  (invariant enforced by {@code CatalogService} and a DB CHECK) — this column is what tells
     *  a "real Інше" position apart from one filed under someone's own trade. ON DELETE SET NULL:
     *  deleting the custom trade just drops the position back to plain OTHER, nothing is lost. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "custom_trade_id")
    private UserTrade customTrade;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ItemType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false, length = 20)
    private Unit unit;

    @Column(name = "default_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal defaultPrice;

    /**
     * Where this row came from — recorded at write time, never inferred later.
     *
     * <p>The admin insight screens read it to tell "the master invented this" from "we gave them
     * this". Deriving it from the default catalog does not work: a position we shipped and later
     * deleted is absent from the templates too, and V70–V73 deleted plenty.
     *
     * <p>Defaults to {@link CatalogItemSource#MANUAL} so a new write path that forgets to set it
     * lands in the candidate list, where a human sees it — rather than being filed as ours and
     * silently disappearing.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    @Builder.Default
    private CatalogItemSource source = CatalogItemSource.MANUAL;

    /**
     * The master's own arrangement, 0-based within HIS catalog (V87).
     *
     * <p>Replaces sorting by category-then-name at read time. Categories have no rows of their own
     * here either — a group IS the run of positions sharing a category, ordered by the first of
     * them — so dragging a whole category is the same write as dragging one position. Same model
     * as {@code EstimateItem.sortOrder}, deliberately: the two screens now behave identically and
     * the client shares one piece of arithmetic between them.</p>
     */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

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
        // Lombok's @Builder ignores field initialisers, so the default above does not survive a
        // builder that omits it — same trap `trade` and `User.role` already carry. MANUAL is the
        // safe side to fail on: a row lands in the admin candidate list instead of being filed
        // as ours and never looked at.
        if (source == null) {
            source = CatalogItemSource.MANUAL;
        }
    }
}
