package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One factor per oblast (V222, level 2 of plan-regional-prices.md): how the oblast's masters price
 * against the national median, over every position both have. Needs 3+ masters in the oblast, not
 * per position — so it exists long before most positions have an oblast median of their own.
 */
@Entity
@Table(name = "regional_index")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "region")
public class RegionalIndex {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "region", nullable = false, length = 30)
    private Region region;

    @Column(name = "factor", nullable = false, precision = 6, scale = 3)
    private BigDecimal factor;

    /** Masters of the oblast whose prices the factor is built from. */
    @Column(name = "masters_count", nullable = false)
    private int mastersCount;

    /** Distinct positions compared with the national median. */
    @Column(name = "positions_count", nullable = false)
    private int positionsCount;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;
}
