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
import java.time.Instant;
import java.util.UUID;

/**
 * One material in the shared dictionary (V126) — a name, how it is measured, and how it is sold.
 *
 * <p>Deliberately carries <b>no price and no owner</b>. V81 removed invented material prices from
 * the product ("a stale guess competing with a real number is worse than no guess"); a price is
 * born either in the master's own catalog or on a shop receipt. This table exists to normalise
 * names and packaging so the calculator can round up to a bag, not to compete with either.</p>
 */
@Entity
@Table(name = "material")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class Material {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * A stable machine name (V127). Norms reference a material by it rather than by name — a name
     * is content and may be reworded — and it is how the engine recognises the one row whose
     * packaging a master parameter overrides ({@code GKL_SHEET}). Null on a row nothing addresses.
     */
    @Column(name = "code", length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /**
     * Specification kept out of the name on purpose: «Лист ГКЛ» + «1200×2500». A parameter asking
     * which sheet the master buys has nothing to choose between if the size is baked into the name.
     */
    @Column(name = "spec", length = 100)
    private String spec;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false, length = 20)
    private Unit unit;

    /** How it is sold: 25 kg of putty, 1000 screws, 10 litres of primer. Null = sold loose. */
    @Column(name = "package_size", precision = 15, scale = 3)
    private BigDecimal packageSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "package_unit", length = 20)
    private Unit packageUnit;

    /**
     * What the package is CALLED: «лист», «мішок», «упаковка». «14 листів» is what the master reads
     * on the shelf; «14 × 3 м²» is the same fact in a language nobody uses in a builders' merchant.
     * Null exactly when {@link #packageSize} is null.
     */
    @Column(name = "package_name", length = 40)
    private String packageName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** «Лист ГКЛ 1200×2500» — what the master reads in the shop. */
    public String displayName() {
        return spec == null || spec.isBlank() ? name : name + " " + spec;
    }

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
