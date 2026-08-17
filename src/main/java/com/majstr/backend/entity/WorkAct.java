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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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
 * A work act (Акт виконаних робіт) — a document built from a signed estimate's positions, signed
 * separately by the client (acts iteration). Its line items ({@link WorkActItem}) are frozen copies
 * so the act reads identically after the underlying estimate is edited or deleted.
 */
@Entity
@Table(name = "work_act")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class WorkAct {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The master (owner). Numbering is continuous per this user; UNIQUE(user_id, number). */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    /** Display number («7» or «7/2026», per {@link User#getActNumberFormat()}). */
    @Column(name = "number", nullable = false, length = 20)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private WorkActKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WorkActStatus status;

    @Column(name = "issued_at", nullable = false)
    private LocalDate issuedAt;

    /** The period this act covers — both mandatory, separate from {@link #issuedAt}. */
    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @Column(name = "place", length = 120)
    private String place;

    @Column(name = "contract_ref", length = 255)
    private String contractRef;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Builder.Default
    @Column(name = "show_materials", nullable = false)
    private boolean showMaterials = true;

    @Builder.Default
    @Column(name = "show_cumulative", nullable = false)
    private boolean showCumulative = true;

    /** Advances to net off against this act's total (→ «До сплати»). */
    @Column(name = "advance_offset", precision = 15, scale = 2)
    private BigDecimal advanceOffset;

    /** Reserved (гарантійне утримання) — no UI yet, laid in for later. */
    @Column(name = "retention_percent", precision = 5, scale = 2)
    private BigDecimal retentionPercent;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "signer_name", length = 255)
    private String signerName;

    @Column(name = "signer_phone", length = 50)
    private String signerPhone;

    @Column(name = "signer_ip", length = 64)
    private String signerIp;

    @Column(name = "signer_user_agent", length = 512)
    private String signerUserAgent;

    /** True when the master signed on the client's behalf via the offline path (no signer_ip/UA). */
    @Builder.Default
    @Column(name = "signed_offline", nullable = false)
    private boolean signedOffline = false;

    /** SHA-256 of the final signed PDF — a cheap tamper-evidence stamp (set at portal-sign, Prompt 5). */
    @Column(name = "doc_hash", length = 64)
    private String docHash;

    /** The auto-created «Додаткові роботи до акта № N» estimate, if this act was signed with
     *  additional positions. {@code ON DELETE SET NULL}. */
    @Column(name = "addendum_estimate_id")
    private UUID addendumEstimateId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

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
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = WorkActStatus.DRAFT;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
