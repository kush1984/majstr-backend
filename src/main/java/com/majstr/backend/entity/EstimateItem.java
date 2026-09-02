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

@Entity
@Table(name = "estimate_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class EstimateItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "estimate_id", nullable = false, updatable = false)
    private Estimate estimate;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ItemType type;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Copied from the catalog item on add; lets the estimate group too. Null = "Без категорії". */
    @Column(name = "category", length = 100)
    private String category;

    /**
     * What the position means in plain words, snapshotted from the catalog when the line was added
     * (V119) — «Q4 (еліт)» is a word only a plasterer knows, and the client reads this name in the
     * portal and in the PDF. Null for a line the master typed himself; most need no explaining.
     *
     * <p>A copy rather than a join, like every other field on this row: the client signed THIS
     * wording, so re-pricing or renaming the catalog position must not change it.</p>
     */
    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false, length = 20)
    private Unit unit;

    @Column(name = "quantity", nullable = false, precision = 15, scale = 3)
    private BigDecimal quantity;

    /**
     * Price per unit — and, for a {@code PERCENT} line whose base is {@link PercentBaseKind#MANUAL},
     * the base sum itself («10 % від 500 ₴»). No new money column: the two readings never coexist
     * on one row.
     */
    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitPrice;

    /**
     * The computed amount of this line, in hryvnia (V88). <b>Written by the server on every write,
     * never accepted from a request.</b>
     *
     * <p>Stored rather than derived because a percentage OF THE SUBTOTAL cannot be expressed as one
     * SQL aggregate — the row depends on the total and the total on the row — and six native
     * queries (dashboard, object economy, project cards) do exactly one aggregate each. See
     * {@code EstimateMath} for the three-step pass that fills it.</p>
     */
    @Column(name = "line_total", nullable = false, precision = 15, scale = 2)
    private BigDecimal lineTotal;

    /** For {@code unit = PERCENT}: what this is a percentage OF. Null on every other row. */
    @Enumerated(EnumType.STRING)
    @Column(name = "percent_base_kind", length = 20)
    private PercentBaseKind percentBaseKind;

    /**
     * The line this percentage is measured against ({@link PercentBaseKind#POSITION}).
     *
     * <p>A plain id rather than an association: the FK is {@code ON DELETE SET NULL}, and the row
     * survives its base on purpose — deleting «Шафа» must not delete «Доставка 10 %» along with it.
     * The master keeps the last computed amount and is told the base is gone.</p>
     */
    @Column(name = "percent_base_item_id")
    private UUID percentBaseItemId;

    /**
     * The live link is off: either the master typed the amount himself, or the base was deleted.
     *
     * <p>Manual wins over automatic — the same rule {@code quantityManual} follows in measurements.
     * A detached line keeps whatever it last computed and is never recalculated.</p>
     */
    @Column(name = "base_detached", nullable = false)
    private boolean baseDetached;

    /**
     * What this line cost in the estimate it was duplicated FROM — the foreman's own cost.
     *
     * <p>Null on an ordinary estimate. Also null on a line ADDED to a duplicate afterwards, and
     * that null is meaningful: nobody is paid for it downstream, so the whole line is margin.</p>
     *
     * <p>This, not the estimate's {@code markupPercent}, is what the object economy subtracts.
     * A percent stops describing the sheet the moment the master marks up only some lines, edits
     * one price, or deletes the parent — this survives all three, because the earning is always
     * the same subtraction against a figure that was true when the copy was made.</p>
     */
    @Column(name = "source_unit_price", precision = 15, scale = 2)
    private BigDecimal sourceUnitPrice;

    /**
     * The line this was copied from, so a deletion in the master-price estimate reaches its twin.
     *
     * <p>Trimming happens in the parent — that is where the 167-position template was applied — and
     * a copy that kept the removed lines would silently undo the work. Deletion flows one way only:
     * parent to duplicate, never back, because the duplicate is the client's sheet and the master
     * edits it on purpose.</p>
     */
    @Column(name = "source_item_id")
    private UUID sourceItemId;

    /**
     * Snapshot of what a FROZEN {@code PERCENT} line meant before it was frozen — the signed
     * percent, what it was a share of (works/materials/a named position), and the source
     * estimate's name. Null on every line that was never copied into a consolidated rollup.
     *
     * <p>Text, not a FK: the source estimate/position can be edited or deleted afterwards, and
     * this must keep reading what was true at consolidation time regardless (same reasoning as
     * {@link #sourceUnitPrice}/{@link #sourceItemId}, V85).</p>
     */
    @Column(name = "base_origin_label", columnDefinition = "text")
    private String baseOriginLabel;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** Measurement elements whose result was summed into {@code quantity} — a JSON array
     *  of measurement_item ids (selection memory for the "Вибрати з замірів" dialog). Null
     *  when the quantity wasn't derived from measurements. Refs to deleted elements are
     *  ignored (never a hard failure). */
    @Column(name = "measurement_refs", columnDefinition = "text")
    private String measurementRefs;

    /** True once the master edited {@code quantity} by hand — then the measurement dialog
     *  must not silently overwrite it (shows the selection as a hint + a soft warning). */
    @Builder.Default
    @Column(name = "quantity_manual", nullable = false)
    private boolean quantityManual = false;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        // line_total is NOT NULL, and Hibernate writes the column explicitly — so the DEFAULT 0 in
        // the migration never applies and a row built before EstimateMath has run would be rejected
        // by the database. Every bulk path (import, duplicate, consolidate, template apply) saves
        // first and recalculates after, which is exactly that case. Zero here is a placeholder the
        // recalculation immediately overwrites, never a number anyone sees.
        if (lineTotal == null) {
            lineTotal = BigDecimal.ZERO;
        }
    }
}
