package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.time.LocalDate;
import java.util.UUID;

/**
 * A planned or received payment against an OBJECT (project), not an estimate — a project has
 * one money relationship with its client even when it carries several estimates (variants,
 * consolidations, drafts). Absorbs the old per-estimate {@code deposit_amount} (V93); a
 * "завдаток" is simply the first row here.
 *
 * <p>{@code amount} (planned) and {@code paidAmount} (actual) are deliberately separate — a
 * client can pay 8 000 against a planned 10 000. {@code dueDate} is not a debt reminder; it is
 * the condition for starting {@code nextStage} of work ("оплатити до 05.09, щоб почати чистові
 * роботи") — UI copy must keep that framing.</p>
 */
@Entity
@Table(name = "project_payment")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ProjectPayment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "next_stage", length = 255)
    private String nextStage;

    @Column(name = "purpose", nullable = false, length = 255)
    private String purpose;

    @Column(name = "paid_amount", precision = 15, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "paid_at")
    private Instant paidAt;

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
    }

    /** Derived, never stored — a status column would drift the moment {@code paidAmount} or
     *  {@code dueDate} changes without a matching write. {@code today} is passed in rather than
     *  read from the clock so this stays a pure, testable function. */
    public ProjectPaymentStatus status(LocalDate today) {
        if (paidAmount != null && paidAmount.compareTo(amount) >= 0) {
            return ProjectPaymentStatus.RECEIVED;
        }
        if (paidAmount != null && paidAmount.signum() > 0) {
            return ProjectPaymentStatus.PARTIAL;
        }
        if (dueDate != null && dueDate.isBefore(today)) {
            return ProjectPaymentStatus.OVERDUE;
        }
        return ProjectPaymentStatus.PLANNED;
    }
}
