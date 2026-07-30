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

import java.time.Instant;
import java.util.UUID;

/**
 * A catalog-insight candidate the owner judged and rejected, so it stops coming back.
 *
 * <p>Keyed by the NORMALISED name rather than by the row that prompted it: the candidate is
 * "this work, however it was spelled", not one master's catalog line. Twenty masters typing the
 * same thing is one decision, not twenty.
 */
@Entity
@Table(name = "catalog_insight_dismissals")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class CatalogInsightDismissal {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private CatalogInsightKind kind;

    @Column(name = "name_key", nullable = false, length = 512)
    private String nameKey;

    /** The key is sorted words and unreadable; this is what a human sees in an audit line. */
    @Column(name = "sample_name", nullable = false, length = 255)
    private String sampleName;

    @Column(name = "dismissed_by")
    private UUID dismissedBy;

    @Column(name = "dismissed_at", nullable = false)
    private Instant dismissedAt;

    @Column(name = "note", length = 500)
    private String note;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (dismissedAt == null) {
            dismissedAt = Instant.now();
        }
    }
}
