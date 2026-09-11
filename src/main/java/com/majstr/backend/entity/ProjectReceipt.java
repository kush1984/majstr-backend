package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.time.LocalDate;
import java.util.UUID;

/**
 * A receipt photographed at the till and filed against the OBJECT (V129) — not against a document.
 *
 * <p>Deliberately a second receipt table beside {@link WorkActReceipt}: that one exists to be
 * re-billed on one act and is frozen into its {@code doc_hash}, while this one is taken before any
 * act exists. Keeping them apart is what lets the till be two taps.</p>
 *
 * <p><b>{@code reimbursable} is the only decision this row carries, and it defaults to true</b>
 * (master's ruling): material money is mostly the client's, so a receipt is a RECEIVABLE, and an
 * expense only when the master says so. {@link #expenseId} is the {@code object_expenses} row this
 * receipt created when he did — written and removed together with the flag, and never present while
 * {@code reimbursable} (a DB CHECK backs that up).</p>
 *
 * <p>{@link #fiscalFn} / {@link #fiscalId} come off the printed fiscal QR and identify the paper
 * exactly. They power a duplicate WARNING on the read path, never a constraint — the photo is saved
 * before anything is read off it, so the identity arrives on a later edit.</p>
 */
@Entity
@Table(name = "project_receipt")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ProjectReceipt {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "label", nullable = false, length = 160)
    private String label;

    @Builder.Default
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "issued_at")
    private LocalDate issuedAt;

    @Column(name = "storage_key", length = 255, updatable = false)
    private String storageKey;

    @Builder.Default
    @Column(name = "reimbursable", nullable = false)
    private boolean reimbursable = true;

    @Column(name = "expense_id")
    private UUID expenseId;

    @Column(name = "fiscal_fn", length = 64)
    private String fiscalFn;

    @Column(name = "fiscal_id", length = 64)
    private String fiscalId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

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
