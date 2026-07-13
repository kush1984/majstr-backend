package com.majstr.backend.dto;

import com.majstr.backend.entity.PhotoSource;
import com.majstr.backend.entity.PhotoVisibility;
import com.majstr.backend.entity.ProjectPhoto;

import java.time.Instant;
import java.util.UUID;

/**
 * An object photo for the owner's «Фото» tab. {@code fileUrl} points at the
 * authenticated stream ({@code /api/projects/{projectId}/photos/{id}/file}) — the
 * opaque storage key is never exposed. {@code estimateName} is the snapshot label for
 * a receipt photo (survives the estimate's deletion).
 */
public record ProjectPhotoResponse(
        UUID id,
        PhotoSource source,
        PhotoVisibility visibility,
        String caption,
        UUID estimateId,
        String estimateName,
        String fileUrl,
        Instant createdAt
) {
    public static ProjectPhotoResponse from(ProjectPhoto p) {
        return new ProjectPhotoResponse(
                p.getId(),
                p.getSource(),
                p.getVisibility(),
                p.getCaption(),
                p.getEstimateId(),
                p.getEstimateNameSnapshot(),
                "/api/projects/" + p.getProjectId() + "/photos/" + p.getId() + "/file",
                p.getCreatedAt()
        );
    }
}
