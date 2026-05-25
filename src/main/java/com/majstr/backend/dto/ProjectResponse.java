package com.majstr.backend.dto;

import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;

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
        Instant createdAt,
        Instant updatedAt
) {
    public static ProjectResponse from(Project project) {
        Client client = project.getClient();
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getAddress(),
                project.getStatus(),
                project.getDescription(),
                client == null ? null : client.getId(),
                client == null ? null : client.getFullName(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
