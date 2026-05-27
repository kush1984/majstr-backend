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

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Global, read-only library of starter catalog items grouped by trade.
 * Copied into a user's own {@link CatalogItem} library on registration
 * (and on demand via the {@code reset-from-template} endpoint).
 */
@Entity
@Table(name = "catalog_templates")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class CatalogTemplate {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "trade", nullable = false, length = 50)
    private Trade trade;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ItemType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false, length = 20)
    private Unit unit;

    @Column(name = "suggested_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal suggestedPrice;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
