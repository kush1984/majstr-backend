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

@Entity
@Table(name = "estimates")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class Estimate {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    /** Optional contractor label to tell variant estimates apart (econom /
     *  premium); null → the client shows a default name. */
    @Column(name = "name", length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private EstimateStatus status;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** Deposit the client pays up front (завдаток); null = none. The balance
     *  (залишок = total − deposit) is computed, never stored. Client-facing. */
    @Column(name = "deposit_amount", precision = 15, scale = 2)
    private BigDecimal depositAmount;

    /** Whether this estimate counts toward the object's economy (income). Default
     *  <b>true</b>: every estimate counts regardless of status, so the economy is
     *  complete out of the box; the owner unchecks what shouldn't count. The one
     *  exception is a <b>consolidated</b> estimate — created with false so it doesn't
     *  double-count its (already-counted) sources. Owner-only, never client-facing. */
    @Builder.Default
    @Column(name = "count_in_economy", nullable = false)
    private boolean countInEconomy = true;

    /** Whether this estimate shows on the object's client portal. The master
     *  picks the set explicitly in the share sheet — nothing is shared by
     *  default. Distinct from legacy per-estimate share links, which stay
     *  usable for URLs already sent out. */
    @Column(name = "portal_visible", nullable = false)
    private boolean portalVisible;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "signer_name", length = 255)
    private String signerName;

    @Column(name = "signer_phone", length = 50)
    private String signerPhone;

    @Column(name = "signer_ip", length = 64)
    private String signerIp;

    // Reopen audit: when the owner re-opened a signed estimate for edits, and
    // who (their user id). Cleared signature + back to DRAFT until re-signed.
    @Column(name = "reopened_at")
    private Instant reopenedAt;

    @Column(name = "reopened_by")
    private UUID reopenedBy;

    // Optimistic lock: two concurrent updates (e.g. parallel portal sign
    // requests) can't both win — the second commit fails with a 409.
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
            status = EstimateStatus.DRAFT;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
