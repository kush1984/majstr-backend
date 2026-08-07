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
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.UUID;

/**
 * A trade a master invented for himself ("Натяжні стелі") — unlike {@link Trade}, a closed enum
 * mirrored into four CHECK constraints, this is just a row with a name. There is no reference
 * catalog behind it and there never will be: the master fills his own catalog under it, position
 * by position. {@link com.majstr.backend.entity.CatalogItem} and
 * {@link com.majstr.backend.entity.EstimateTemplate} (own templates only) reference it by a
 * nullable FK rather than by widening the enum.
 *
 * <p>{@code @BatchSize}: every {@code CatalogItemResponse}/template response touches its lazy
 * {@code customTrade} proxy to read the name — without batching, listing N positions filed under
 * custom trades would be N extra selects instead of one IN-clause.</p>
 */
@Entity
@Table(name = "user_trade")
@BatchSize(size = 50)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class UserTrade {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** The master's own ordering in the trade picker/profile list. */
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
