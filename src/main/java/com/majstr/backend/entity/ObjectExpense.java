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
 * One expense a master logged against an object (project) — money spent on materials,
 * labour, or other, with an optional note and a spend date. Owner-scoped via the
 * parent object; PRO-gated at the service layer. Money is {@link BigDecimal}(15,2),
 * the same as estimate prices — no new format. Plain {@code objectId} (no association)
 * mirrors {@code Payment}/{@code UpgradeEvent}.
 */
@Entity
@Table(name = "object_expenses")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ObjectExpense {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "object_id", nullable = false, updatable = false)
    private UUID objectId;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private ExpenseCategory category;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "spent_at", nullable = false)
    private LocalDate spentAt;

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
        if (spentAt == null) {
            spentAt = LocalDate.now();
        }
    }
}
