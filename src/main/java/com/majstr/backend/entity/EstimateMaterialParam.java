package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.UUID;

/**
 * One figure the material calculator asked the master for, and he answered (V142).
 *
 * <p>The calculation stores nothing — it is recomputed from the estimate on every request. These
 * three are not computed from anything: a room's {@link NormBasis#PERIMETER}, a короб's розгортка
 * ({@link NormBasis#SECTION}, V131) and a layer's {@link NormBasis#THICKNESS} in millimetres
 * (V137) are answers only the master has. They used to live in the browser's {@code localStorage},
 * which meant the same estimate opened on his laptop asked all of them again.</p>
 *
 * <p><b>The estimate and the item are held as raw ids, not as associations</b>, and that is the
 * point of the table: writing an answer here must never touch the {@code Estimate} aggregate, whose
 * {@code @Version} rides into the portal render and back on the client's sign request (V141, 409
 * {@code ESTIMATE_CHANGED}). Answering a thickness is not an edit of the document. The FKs are
 * declared in the migration with {@code ON DELETE CASCADE}, so a deleted line takes its answers with
 * it without a single Java line knowing.</p>
 *
 * <p>{@link #estimateItemId} is null exactly for {@code PERIMETER} — one room, one perimeter — and
 * a CHECK constraint keeps that exact. A cleared field DELETES the row: the calculator reads a
 * non-positive parameter as «not answered», so storing 0 would be a second spelling of the same
 * state.</p>
 */
@Entity
@Table(name = "estimate_material_param")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class EstimateMaterialParam {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "estimate_id", nullable = false, updatable = false)
    private UUID estimateId;

    /** Null exactly for {@link NormBasis#PERIMETER}. */
    @Column(name = "estimate_item_id", updatable = false)
    private UUID estimateItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "basis", nullable = false, length = 20, updatable = false)
    private NormBasis basis;

    /** Metres for a perimeter or a розгортка, millimetres for a thickness. Always {@code > 0}. */
    @Column(name = "value", nullable = false, precision = 12, scale = 3)
    private BigDecimal value;

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
