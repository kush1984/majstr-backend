package com.majstr.backend.dto;

import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ObjectStage;
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
        /** The ONE status vocabulary the UI shows now (object-status-unification) — the card
         *  badge, the list filter chips, and the dashboard metrics all read this instead of
         *  {@code status} or the latest estimate's own status. See {@link ObjectStage#derive}. */
        ObjectStage stage,
        String description,
        UUID clientId,
        String clientFullName,
        // Card summary: the project's latest estimate (by createdAt). Both null
        // when the project has no estimate yet.
        BigDecimal latestEstimateTotal,
        EstimateStatus estimateStatus,
        // Unread client questions on this project — drives the card's 💬 indicator.
        long unreadQuestions,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt
) {
    /** Use when neither the latest-estimate summary nor the stage flags are loaded (e.g. a
     *  freshly created project, which by definition has no estimates yet). */
    public static ProjectResponse from(Project project) {
        return from(project, null, null, 0L, false, false);
    }

    public static ProjectResponse from(Project project, BigDecimal latestEstimateTotal,
                                       EstimateStatus estimateStatus, long unreadQuestions,
                                       boolean hasSigned, boolean hasSent) {
        Client client = project.getClient();
        ObjectStage stage = ObjectStage.derive(project.getStatus(), project.getCompletedAt(), hasSigned, hasSent);
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getAddress(),
                project.getStatus(),
                stage,
                project.getDescription(),
                client == null ? null : client.getId(),
                client == null ? null : client.getFullName(),
                latestEstimateTotal,
                estimateStatus,
                unreadQuestions,
                project.getCompletedAt(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
