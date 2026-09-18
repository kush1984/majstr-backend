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
import java.time.LocalDate;
import java.util.UUID;

/**
 * One line of the master's OWN money (V135) — money that belongs to him, not to any object.
 *
 * <p><b>This table holds only what the objects do not already know.</b> Fuel, tools, rent, taxes,
 * and income for work that closed without an act. Money that belongs to an object keeps being
 * written to that object's own journal ({@code payment_receipt} / {@code object_expenses}); the cash
 * screen unions the three sources when it reads. A row here that merely NAMED an object would leave
 * that object's economy saying «Отримано 0» while this book said otherwise — and two books that
 * disagree is how a master stops trusting both.</p>
 *
 * <p>Owner-scoped by a plain {@code ownerId}, like {@link ObjectExpense}'s {@code objectId}: there
 * is nothing to navigate to from here.</p>
 */
@Entity
@Table(name = "cash_entry")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class CashEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private CashDirection direction;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /** Optional by design — see {@link CashCategory}. An uncategorised row is an ordinary row. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20)
    private CashCategory category;

    @Column(name = "note", length = 500)
    private String note;

    /** The authoritative day. Editable; the client sends today unless the master changes it. */
    @Column(name = "happened_on", nullable = false)
    private LocalDate happenedOn;

    /**
     * Stamped automatically at entry and only surfaced if the master goes looking for it: its whole
     * job is ordering rows inside one day, and a time picker on every entry is friction for nothing
     * else. Editing the DAY leaves it as it was — the time is «when I wrote this», not a second
     * calendar field to keep consistent.
     */
    @Column(name = "happened_at", nullable = false)
    private Instant happenedAt;

    /**
     * Money the client paid BACK for material the master laid out.
     *
     * <p>It stays in the cash movement — it really did arrive — but leaves «Заробив», because
     * counting it as earnings inflates a month by exactly the material. Refused on an EXPENSE by a
     * DB CHECK: there it would mean nothing and would skew the same figure the other way.</p>
     */
    @Builder.Default
    @Column(name = "material_refund", nullable = false)
    private boolean materialRefund = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (happenedAt == null) {
            happenedAt = now;
        }
        if (happenedOn == null) {
            happenedOn = LocalDate.now();
        }
    }
}
