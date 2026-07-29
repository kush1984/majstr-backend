package com.majstr.backend.service;

import com.majstr.backend.dto.ProjectRequest;
import com.majstr.backend.dto.ProjectResponse;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectPhoto;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Limit;
import com.majstr.backend.feature.LimitService;
import com.majstr.backend.repository.ProjectMessageRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectPhotoRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final EstimateRepository estimateRepository;
    private final ProjectMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ClientService clientService;
    private final LimitService limitService;
    private final ProjectPhotoRepository photoRepository;
    private final StorageService storage;

    @Transactional
    public ProjectResponse create(ProjectRequest req, UUID ownerId) {
        return create(req, ownerId, null);
    }

    /**
     * Create a project, optionally with a CLIENT-PROVIDED id (offline authoring). The id makes the
     * create idempotent: a replayed offline create returns the existing project (never a duplicate,
     * and never a second hit on the FREE limit); a foreign id is rejected. The idempotency check
     * runs BEFORE the limit check so a replay of an already-counted project can't spuriously fail.
     */
    @Transactional
    public ProjectResponse create(ProjectRequest req, UUID ownerId, UUID requestedId) {
        if (requestedId != null) {
            var existing = projectRepository.findById(requestedId);
            if (existing.isPresent()) {
                Project p = existing.get();
                if (!p.getOwner().getId().equals(ownerId)) {
                    throw new AccessDeniedException("Project does not belong to the current user");
                }
                return ProjectResponse.from(p); // idempotent replay
            }
        }
        limitService.requireWithinLimit(ownerId, Limit.MAX_PROJECTS);
        User owner = userRepository.getReferenceById(ownerId);
        Client client = req.clientId() == null ? null : clientService.loadOwned(req.clientId(), ownerId);
        Project project = Project.builder()
                .id(requestedId)
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
        // One aggregate query each for the latest-estimate summary and the unread
        // question count across the whole list — no N+1.
        List<UUID> projectIds = projects.stream().map(Project::getId).toList();
        Map<UUID, EstimateSummary> summaries = loadLatestEstimateSummaries(projectIds);
        Map<UUID, Long> unread = loadUnreadCounts(projectIds);
        return projects.stream()
                .map(p -> toResponse(p, summaries.get(p.getId()), unread.getOrDefault(p.getId(), 0L)))
                .toList();
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

    /**
     * Deletes an object and everything under it. <b>Idempotent</b> — an object that is already
     * gone is a no-op, not a 404, so a replayed offline delete (one whose response was lost)
     * does not come back to the master as "not saved to cloud". Ownership is still enforced
     * whenever the row exists.
     */
    @Transactional
    public void delete(UUID id, UUID ownerId) {
        Project project = projectRepository.findById(id).orElse(null);
        if (project == null) {
            return;
        }
        if (!project.getOwner().getId().equals(ownerId)) {
            throw new AccessDeniedException("Project does not belong to the current user");
        }
        // The photo ROWS cascade with the FK, but the stored objects behind them do not:
        // every project delete used to leak all its files on R2/local storage forever. That
        // is cost creep, and — since receipt photos are financial personal data — a deletion
        // that quietly keeps the data. Collect the keys BEFORE the rows disappear.
        List<String> photoKeys = photoRepository.findByProjectIdOrderByCreatedAtDesc(id).stream()
                .map(ProjectPhoto::getStorageKey)
                .filter(k -> k != null && !k.isBlank())
                .toList();

        projectRepository.delete(project);
        // Estimates and items are cascaded by the FK ON DELETE CASCADE.

        // Fail-soft, same as the single-photo path: a storage hiccup must not roll back a
        // delete the master already confirmed — a leftover object is recoverable, a
        // half-deleted project is not.
        for (String key : photoKeys) {
            try {
                storage.delete(key);
            } catch (IOException e) {
                log.warn("Could not delete stored photo {} of project {}: {}", key, id, e.getMessage());
            }
        }
    }

    /** Load a project or throw — existence (404) + ownership (403). Public so
     *  services in sub-packages (e.g. measurements) can reuse the same owner guard. */
    public Project loadOwned(UUID id, UUID ownerId) {
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
        EstimateSummary summary = loadLatestEstimateSummaries(List.of(project.getId())).get(project.getId());
        long unread = messageRepository.countByProjectIdAndReadFalse(project.getId());
        return toResponse(project, summary, unread);
    }

    private static ProjectResponse toResponse(Project project, EstimateSummary summary, long unreadQuestions) {
        return summary == null
                ? ProjectResponse.from(project, null, null, unreadQuestions)
                : ProjectResponse.from(project, summary.total(), summary.status(), unreadQuestions);
    }

    private Map<UUID, Long> loadUnreadCounts(Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> result = new HashMap<>();
        for (Object[] row : messageRepository.countUnreadByProjectIds(projectIds)) {
            result.put((UUID) row[0], (Long) row[1]);
        }
        return result;
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
