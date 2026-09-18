package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.Locale;
import java.util.UUID;

/**
 * One line of a {@link ShoppingList} (V126).
 *
 * <p>Name and unit are FROZEN COPIES, like an estimate line's: the dictionary may be renamed and
 * the master's list must not change under him. {@link #materialId} is a pointer for merging, not
 * the source of what is displayed.</p>
 *
 * <p>Two rules the money side depends on, both enforced here and in {@code ShoppingListService}:
 * a recalculation REPLACES the contribution of its own {@link #sourceEstimateId} (never adds to
 * it), and a row that is {@link #bought} or {@link #clearedAt cleared} is never modified by one.
 * The partial unique index {@code ux_shopping_list_item_open} is the schema-level half of that:
 * at most one OPEN row per (list, source estimate, material).</p>
 */
@Entity
@Table(name = "shopping_list_item")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ShoppingListItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "shopping_list_id", nullable = false, updatable = false)
    private UUID shoppingListId;

    /** Null for a hand-written row the dictionary has never heard of. */
    @Column(name = "material_id")
    private UUID materialId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false, length = 20)
    private Unit unit;

    @Column(name = "quantity", nullable = false, precision = 15, scale = 3)
    private BigDecimal quantity;

    @Builder.Default
    @Column(name = "bought", nullable = false)
    private boolean bought = false;

    @Column(name = "bought_at")
    private Instant boughtAt;

    /** Hidden by «Очистити куплені». Still settled, which is what stops a re-add. */
    @Column(name = "cleared_at")
    private Instant clearedAt;

    /** The master corrected the quantity by hand; a recalculation reports the difference instead. */
    @Builder.Default
    @Column(name = "edited", nullable = false)
    private boolean edited = false;

    /**
     * What the last recalculation would have written here, parked because {@link #edited} says
     * the number is the master's (V128). Null once he has decided, either way.
     */
    @Column(name = "suggested_quantity", precision = 15, scale = 3)
    private BigDecimal suggestedQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private ShoppingListItemSource source;

    /** Mandatory for {@link ShoppingListItemSource#CALCULATOR} — see the class javadoc. */
    @Column(name = "source_estimate_id")
    private UUID sourceEstimateId;

    /** Kept for a later plan-vs-fact comparison; nothing reads it yet. */
    @Column(name = "estimate_item_id")
    private UUID estimateItemId;

    @Column(name = "note", length = 500)
    private String note;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Bought or cleared: settled rows are read by a recalculation but never written by one. */
    public boolean settled() {
        return bought || clearedAt != null;
    }

    /**
     * The master left something of his own on this row, so a recalculation may restate its figure
     * but must never delete the row from under him. A hand-typed quantity ({@code edited}) was
     * always treated this way; a NOTE was not, and «взяти в Епіцентрі, спитати Сергія» vanished the
     * moment the position left the estimate.
     *
     * <p>Deliberately NOT the same thing as {@code edited}: a note says something about the
     * material, not about the number, so the calculator still owns the quantity here.</p>
     */
    public boolean authoredByMaster() {
        return edited || (note != null && !note.isBlank());
    }

    /**
     * The merge key, mirroring the {@code dedup_key} generated column: a dictionary material merges
     * by id, a hand-written row by its normalised name and unit. Change one side and rows that the
     * database considers duplicates stop merging in Java (or the reverse) — keep them identical.
     */
    public String dedupKey() {
        return dedupKey(materialId, name, unit);
    }

    public static String dedupKey(UUID materialId, String name, Unit unit) {
        if (materialId != null) {
            return materialId.toString();
        }
        String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return normalized + "|" + (unit == null ? "" : unit.name());
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
