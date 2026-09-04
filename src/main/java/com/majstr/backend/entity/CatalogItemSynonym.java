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

import java.time.Instant;
import java.util.UUID;

/**
 * A per-master «say X, mean THIS catalog row» taught during a dictation review.
 *
 * <p>Consulted by {@code CatalogMatcher} before the stemmed Dice pass, so a taught wording wins the
 * match outright. FK to {@link CatalogItem} is ON DELETE CASCADE deliberately — see V124 header and
 * docs/open-questions.md → "A learned synonym outlives the catalog position it points at".</p>
 */
@Entity
@Table(name = "catalog_item_synonym")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class CatalogItemSynonym {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "catalog_item_id", nullable = false)
    private CatalogItem catalogItem;

    /** Normalized spoken form — {@code CatalogMatcher.normalize()} of {@link #spokenRaw}. */
    @Column(name = "spoken_normalized", nullable = false, length = 200)
    private String spokenNormalized;

    /** Verbatim spoken form; unread today, kept for a future «розпізнається також як: …» surface. */
    @Column(name = "spoken_raw", nullable = false, length = 200)
    private String spokenRaw;

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
