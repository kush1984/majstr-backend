package com.majstr.backend.dto;

import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        String name,
        String address,
        ProjectStatus status,
        String description,
        UUID clientId,
        String clientFullName,
        // Card summary: the project's latest estimate (by createdAt). Both null
        // when the project has no estimate yet.
        BigDecimal latestEstimateTotal,
        EstimateStatus estimateStatus,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt
) {
    /** Use when the latest-estimate summary isn't loaded (e.g. a freshly created project). */
    public static ProjectResponse from(Project project) {
        return from(project, null, null);
    }

    public static ProjectResponse from(Project project, BigDecimal latestEstimateTotal, EstimateStatus estimateStatus) {
        Client client = project.getClient();
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getAddress(),
                project.getStatus(),
                project.getDescription(),
                client == null ? null : client.getId(),
                client == null ? null : client.getFullName(),
                latestEstimateTotal,
                estimateStatus,
                project.getCompletedAt(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
