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
import java.util.UUID;

/**
 * One thing the parse found in an {@link InboxEntry} (V211). Applying it goes through the record's
 * OWN service; {@code recordId} is what that created, so undo deletes it through the same door.
 */
@Entity
@Table(name = "inbox_item")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class InboxItem {

    public enum Kind { SHOPPING, TASK, NOTE, ESTIMATE_LINE, EXPENSE, INCOME }

    public enum Status { PENDING, APPLIED, DISCARDED }

    /**
     * Why an item waits instead of being applied. ESTIMATE = the object has several open estimates
     * (which one is a question); NO_DRAFT = it has none open — every one is signed, or there is none —
     * and a new one is the master's tap, never the bot's own idea.
     */
    public enum Issue { OBJECT, ESTIMATE, NO_DRAFT, PRICE, UNIT, QUANTITY, AMOUNT, CONFIRM }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "entry_id", nullable = false, updatable = false)
    private UUID entryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "estimate_id")
    private UUID estimateId;

    @Column(name = "text", nullable = false, length = 2000)
    private String text;

    @Column(name = "quantity", precision = 15, scale = 3)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", length = 16)
    private Unit unit;

    @Column(name = "unit_price", precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", length = 16)
    private ItemType itemType;

    @Column(name = "amount", precision = 15, scale = 2)
    private BigDecimal amount;

    /** The widest money vocabulary ({@link CashCategory}); the object journal maps it down. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 16)
    private CashCategory category;

    @Column(name = "record_id")
    private UUID recordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue", length = 16)
    private Issue issue;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
