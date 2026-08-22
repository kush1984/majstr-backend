package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A custom folder of the object's Фото tab (photo-folders). Persisted so an EMPTY folder survives
 * (master decision — created ahead of the photos it will hold). The two defaults are virtual and
 * never stored: «Чеки» = {@link ProjectPhoto#FOLDER_RECEIPTS}, «Інше» = a null folder on the photo.
 * UNIQUE(project_id, name); photos reference the folder by NAME (a label, not an FK), and deletion
 * is refused while any photo carries it.
 */
@Entity
@Table(name = "project_photo_folder")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectPhotoFolder {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

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
