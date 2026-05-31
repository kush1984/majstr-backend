package com.majstr.backend.service;

import com.majstr.backend.dto.ProjectRequest;
import com.majstr.backend.dto.ProjectResponse;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Limit;
import com.majstr.backend.feature.LimitService;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final EstimateRepository estimateRepository;
    private final UserRepository userRepository;
    private final ClientService clientService;
    private final LimitService limitService;

    @Transactional
    public ProjectResponse create(ProjectRequest req, UUID ownerId) {
        limitService.requireWithinLimit(ownerId, Limit.MAX_PROJECTS);
        User owner = userRepository.getReferenceById(ownerId);
        Client client = req.clientId() == null ? null : clientService.loadOwned(req.clientId(), ownerId);
        Project project = Project.builder()
                .owner(owner)
                .client(client)
                .name(req.name().trim())
                .address(req.address().trim())
                .description(normalize(req.description()))
                .status(ProjectStatus.DRAFT)
                .build();
        // A brand-new project has no estimate yet, so the card summary is null.
        return ProjectResponse.from(projectRepository.save(project));
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> listForOwner(UUID ownerId, ProjectStatus status) {
        List<Project> projects = status == null
                ? projectRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId)
                : projectRepository.findByOwnerIdAndStatusOrderByCreatedAtDesc(ownerId, status);
        if (projects.isEmpty()) {
            return List.of();
        }
        // One aggregate query for every project's latest-estimate summary — no N+1.
        Map<UUID, EstimateSummary> summaries =
                loadLatestEstimateSummaries(projects.stream().map(Project::getId).toList());
        return projects.stream().map(p -> toResponse(p, summaries.get(p.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(UUID id, UUID ownerId) {
        return withSummary(loadOwned(id, ownerId));
    }

    @Transactional
    public ProjectResponse update(UUID id, ProjectRequest req, UUID ownerId) {
        Project project = loadOwned(id, ownerId);
        project.setName(req.name().trim());
        project.setAddress(req.address().trim());
        project.setDescription(normalize(req.description()));
        project.setClient(req.clientId() == null ? null : clientService.loadOwned(req.clientId(), ownerId));
        // Editing a project never touches status, so completedAt is preserved.
        return withSummary(project);
    }

    @Transactional
    public ProjectResponse updateStatus(UUID id, ProjectStatus status, UUID ownerId) {
        Project project = loadOwned(id, ownerId);
        applyCompletedAt(project, status);
        project.setStatus(status);
        return withSummary(project);
    }

    @Transactional
    public void delete(UUID id, UUID ownerId) {
        Project project = loadOwned(id, ownerId);
        projectRepository.delete(project);
        // Estimates and items are cascaded by the FK ON DELETE CASCADE.
    }

    Project loadOwned(UUID id, UUID ownerId) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + id));
        if (!project.getOwner().getId().equals(ownerId)) {
            throw new AccessDeniedException("Project does not belong to the current user");
        }
        return project;
    }

    /** Stamp completedAt when entering COMPLETED (only if unset), clear it when leaving. */
    private static void applyCompletedAt(Project project, ProjectStatus newStatus) {
        if (newStatus == ProjectStatus.COMPLETED) {
            if (project.getCompletedAt() == null) {
                project.setCompletedAt(Instant.now());
            }
        } else {
            project.setCompletedAt(null);
        }
    }

    private ProjectResponse withSummary(Project project) {
        return toResponse(project, loadLatestEstimateSummaries(List.of(project.getId())).get(project.getId()));
    }

    private static ProjectResponse toResponse(Project project, EstimateSummary summary) {
        return summary == null
                ? ProjectResponse.from(project)
                : ProjectResponse.from(project, summary.total(), summary.status());
    }

    private Map<UUID, EstimateSummary> loadLatestEstimateSummaries(Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, EstimateSummary> result = new HashMap<>();
        for (Object[] row : estimateRepository.findLatestEstimateSummaries(projectIds)) {
            UUID projectId = (UUID) row[0];
            EstimateStatus status = EstimateStatus.valueOf((String) row[1]);
            BigDecimal total = toBigDecimal(row[2]).setScale(2, RoundingMode.HALF_UP);
            result.put(projectId, new EstimateSummary(total, status));
        }
        return result;
    }

    /** Native SUM can come back as BigDecimal (Postgres numeric); stay robust to other numerics. */
    private static BigDecimal toBigDecimal(Object value) {
        return value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString());
    }

    private record EstimateSummary(BigDecimal total, EstimateStatus status) {}

    private static String normalize(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
