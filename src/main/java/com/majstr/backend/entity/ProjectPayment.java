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
 * A PLANNED payment stage against an OBJECT (project), not an estimate — a project has one money
 * relationship with its client even when it carries several estimates (variants, consolidations,
 * drafts). Absorbs the old per-estimate {@code deposit_amount} (V93); a "завдаток" is simply the
 * first row here.
 *
 * <p>{@code dueDate} is not a debt reminder; it is the condition for starting {@code nextStage} of
 * work ("оплатити до 05.09, щоб почати чистові роботи") — UI copy must keep that framing.</p>
 *
 * <p><b>Payments PLAN/FACT split (V100):</b> the actual money received against a stage lives in
 * {@link PaymentReceipt} now — a stage can be closed by several of them. {@code paidAmount}/
 * {@code paidAt} below are DEPRECATED: still physically present (unread by new code, drop
 * deferred to open-questions) but never written or read for anything money-related anymore.</p>
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

    /** @deprecated (V100) — fact moved to {@link PaymentReceipt}; unread by new code. */
    @Deprecated
    @Column(name = "paid_amount", precision = 15, scale = 2)
    private BigDecimal paidAmount;

    /** @deprecated (V100) — see {@link #paidAmount}. */
    @Deprecated
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

    /** Derived, never stored — a status column would drift the moment the linked
     *  {@link PaymentReceipt}s or {@code dueDate} change without a matching write. {@code today}
     *  and {@code received} (Σ of this stage's receipts) are passed in rather than read from the
     *  clock/database, so this stays a pure, testable function. */
    public ProjectPaymentStatus status(LocalDate today, BigDecimal received) {
        if (received != null && received.compareTo(amount) >= 0) {
            return ProjectPaymentStatus.RECEIVED;
        }
        if (received != null && received.signum() > 0) {
            return ProjectPaymentStatus.PARTIAL;
        }
        if (dueDate != null && dueDate.isBefore(today)) {
            return ProjectPaymentStatus.OVERDUE;
        }
        return ProjectPaymentStatus.PLANNED;
    }
}
