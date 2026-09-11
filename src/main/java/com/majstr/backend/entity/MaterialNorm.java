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

/**
 * How much of a {@link Material} one unit of a kind of work consumes (V126).
 *
 * <p>Keyed by NAME and UNIT, with no foreign key to the catalog: {@code catalog_items} has no link
 * to {@code catalog_templates}, and the templates are deleted and recreated by every catalog
 * rebuild (V82, V116, V122), so an FK to either is broken by construction.</p>
 *
 * <p>{@link #trade} is stored but is only the <b>first rung</b> of the lookup — see
 * {@code MaterialNormRepository}. {@code estimate_items.trade} is nullable by design (V125) and
 * V118 files a position two trades both ship under only one of them, so a lookup that insists on
 * the trade misses silently.</p>
 *
 * <p>{@link #owner} is null on a shipped norm. A master who corrects a coefficient gets a row of
 * his own here, forked on write like {@code TemplateDefaultOverride} (V113) rather than through a
 * parallel "master coefficient" mechanism beside it. His row HIDES the default it was copied from,
 * matched on the natural key {@code ux_material_norm} already enforces — (owner, trade, name, unit,
 * material) — because a shipped norm is recreated by every catalog rebuild and a stored link to one
 * would not survive it.</p>
 */
@Entity
@Table(name = "material_norm")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class MaterialNorm {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Null = a default norm we ship. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "trade", length = 50)
    private Trade trade;

    /** {@code NameKeys.of(position name)} — the same key the template price resolution uses. */
    @Column(name = "name_key", nullable = false, length = 255)
    private String nameKey;

    /** The POSITION's unit, never the material's: a per-м.п. position consumes "per 1 м.п.". */
    @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false, length = 20)
    private Unit unit;

    /**
     * Null means «checked, this work consumes no material» — a recorded verdict, not a gap (V127).
     * Demolition, dust removal and sanding legitimately buy nothing; left unnormed they would show
     * up in the coverage report as data we failed to fill in, which is noise in the one widget
     * whose whole job is trust. The engine counts such a position as covered and never lists it.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id")
    private Material material;

    /** Null exactly when {@link #material} is null — a CHECK constraint pairs them. */
    @Column(name = "qty_per_unit", precision = 15, scale = 4)
    private BigDecimal qtyPerUnit;

    /** What {@link #qtyPerUnit} multiplies — the line's own quantity, or the room's perimeter. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "basis", nullable = false, length = 20)
    private NormBasis basis = NormBasis.QUANTITY;

    @Builder.Default
    @Column(name = "waste_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal wastePercent = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

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
