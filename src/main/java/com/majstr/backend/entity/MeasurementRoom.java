package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A room (or zone) of an object's measurements — «Спальня», «Ванна». Belongs to a
 * {@link Project} by id (FK ON DELETE CASCADE in the migration, so deleting the object
 * drops its rooms and their items). Owner isolation is enforced via the project in the
 * service, not here.
 */
@Entity
@Table(name = "measurement_room")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class MeasurementRoom {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Free-text floor label («1», «2», «цоколь», «мансарда»), null = none. An attribute,
     *  deliberately not a hierarchy level — the list groups by exact string match. */
    @Column(name = "floor", length = 20)
    private String floor;

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
