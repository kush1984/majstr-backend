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
import java.util.UUID;

/**
 * One line of a {@link WorkAct} — a FROZEN copy of an estimate position (name/category/unit/price),
 * plus the quantity done in THIS act and the cumulative done before it (acts iteration).
 *
 * <p>Frozen by value, not FK: the estimate line can be edited or deleted afterwards, but an act
 * already sent to the client must read identically a year later — the same pattern as
 * {@code EstimateItem.sourceUnitPrice}/{@code baseOriginLabel}.</p>
 */
@Entity
@Table(name = "work_act_item")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class WorkActItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_act_id", nullable = false, updatable = false)
    private WorkAct workAct;

    /** The estimate line this closes work against, or {@code null} for an ADDITIONAL work not in any
     *  estimate. {@code ON DELETE SET NULL} — the frozen line survives the estimate line's deletion. */
    @Column(name = "estimate_item_id")
    private UUID estimateItemId;

    /** Which estimate this belongs to, for grouping in the PDF (frozen; {@code ON DELETE SET NULL}). */
    @Column(name = "estimate_id")
    private UUID estimateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ItemType type;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Frozen; the PDF groups by it, as the estimate does. Null = «Без категорії». */
    @Column(name = "category", length = 100)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false, length = 20)
    private Unit unit;

    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitPrice;

    /** Quantity done in THIS act. */
    @Column(name = "quantity", nullable = false, precision = 15, scale = 3)
    private BigDecimal quantity;

    /** {@code unitPrice × quantity} — server-authored, never from a request. */
    @Column(name = "line_total", nullable = false, precision = 15, scale = 2)
    private BigDecimal lineTotal;

    /** How much of this estimate line was already done in prior SIGNED acts, frozen at act creation
     *  and never recomputed — so the numbers in a sent act don't drift between openings. */
    @Column(name = "cumulative_before", nullable = false, precision = 15, scale = 3)
    private BigDecimal cumulativeBefore;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
