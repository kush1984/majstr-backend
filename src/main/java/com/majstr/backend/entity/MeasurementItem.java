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
 * One measured element inside a {@link MeasurementRoom} — «Стеля», «Душова перегородка»,
 * «Відкоси вікна». {@code result} is the metric computed server-side (m² or м.пог, the
 * source of truth used for totals and substitution); {@code payload} is the raw entered
 * data as a JSON string, kept so the element can be reopened and re-edited. {@code unit}
 * is derived from {@code type} at create time (drives the substitution filter).
 */
@Entity
@Table(name = "measurement_item")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class MeasurementItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "room_id", nullable = false, updatable = false)
    private UUID roomId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private MeasurementType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false, length = 20)
    private Unit unit;

    @Column(name = "result", nullable = false, precision = 15, scale = 3)
    private BigDecimal result;

    /** Raw entered data as a JSON string (segments/openings, or dims + faces/sides). */
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
