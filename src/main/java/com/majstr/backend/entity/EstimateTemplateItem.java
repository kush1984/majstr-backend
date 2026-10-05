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

import java.util.UUID;

/**
 * One position inside an {@link EstimateTemplate} — a work/material name + unit,
 * with no quantity and no price. On apply, each becomes a real {@link EstimateItem}
 * whose quantity starts empty (filled per object) and whose price is looked up in
 * the applying master's own catalog by name (empty if not found).
 */
@Entity
@Table(name = "estimate_template_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class EstimateTemplateItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false, updatable = false)
    private EstimateTemplate template;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ItemType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false, length = 20)
    private Unit unit;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /**
     * The SYSTEM DEFAULT position this row was copied from when the bundle was forked on write
     * (V113), or {@code null} for an ordinary own position (review B-34).
     *
     * <p>It exists so a LATER request can be translated too. V113 hands the forking request a
     * default-id → copy-id map built at the moment of the copy, but the PWA's outbox replays every
     * queued op addressing the DEFAULT's ids: op 1 forked and landed, ops 2..n found the fork already
     * there, were handed an EMPTY map, matched nothing, and were answered as SUCCESS. An offline batch
     * lost everything but its first op, and the editor — which re-seeds its baseline from the answer —
     * showed a bundle that looked saved.</p>
     *
     * <p>{@code ON DELETE SET NULL}: a shipped position can be deleted by a later catalog rebuild
     * (V116, V121 and V122 all do), and losing the pointer must not take the master's copy with it.</p>
     */
    @Column(name = "forked_from_item_id")
    private UUID forkedFromItemId;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
