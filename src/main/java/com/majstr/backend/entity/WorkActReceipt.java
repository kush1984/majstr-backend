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
 * One receipt or invoice attached to a {@link WorkAct} — a label, an amount typed by the master and
 * (optionally) a photo of the paper (act-receipts iteration). Materials the master bought and
 * re-bills on the act; the line items on the paper are deliberately NOT parsed — the master's own
 * request was «чек 1 — сума, чек 2 — сума, разом».
 *
 * <p>The photo is an ACT-OWNED copy in storage, never a reference to a {@code ProjectPhoto}: an act
 * is a frozen document, so deleting an object photo must not change what a signed act shows — or
 * break its {@code doc_hash}, which covers the receipts block.</p>
 */
@Entity
@Table(name = "work_act_receipt")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class WorkActReceipt {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_act_id", nullable = false, updatable = false)
    private WorkAct workAct;

    @Column(name = "label", nullable = false, length = 160)
    private String label;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /** The date printed on the receipt, if the master bothered to type it. */
    @Column(name = "issued_at")
    private LocalDate issuedAt;

    /** Act-owned storage key of the photo, or null when only an amount was entered. */
    @Column(name = "storage_key", length = 255, updatable = false)
    private String storageKey;

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
}
