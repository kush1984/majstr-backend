package com.majstr.backend.service;

import com.majstr.backend.dto.ProjectRequest;
import com.majstr.backend.dto.ProjectResponse;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ObjectStage;
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
import com.majstr.backend.repository.ProjectReceiptRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.ShoppingListItemRepository;
import com.majstr.backend.repository.ShoppingListRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.stream.Stream;

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
    private final ProjectReceiptRepository projectReceiptRepository;
    private final WorkActReceiptRepository workActReceiptRepository;
    private final StorageCleanup cleanup;
    private final ShoppingListRepository shoppingListRepository;
    /** Bulk queries only, like {@link ShoppingListRepository#updateArchivedAt}: this service must
     *  not depend on {@code ShoppingListService}, which depends on it back. */
    private final ShoppingListItemRepository shoppingListItemRepository;

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
        limitService.reserveProjectSlot(ownerId);
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

    /** {@code stage} filters on the DERIVED {@link ObjectStage}, not the raw {@link ProjectStatus}
     *  column (object-status-unification) — fetches the owner's whole list and filters in memory
     *  after computing each project's stage, rather than translating the priority chain into SQL.
     *  Simple, and cheap enough at a solo master's object count (same trade-off the admin
     *  {@code MetricsService} already accepts at larger scale — see open-questions.md). */
    @Transactional(readOnly = true)
    public List<ProjectResponse> listForOwner(UUID ownerId, ObjectStage stage) {
        List<Project> projects = projectRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
        if (projects.isEmpty()) {
            return List.of();
        }
        // One aggregate query each for the latest-estimate summary, the unread question count,
        // and the SIGNED/SENT stage flags across the whole list — no N+1.
        List<UUID> projectIds = projects.stream().map(Project::getId).toList();
        Map<UUID, EstimateSummary> summaries = loadLatestEstimateSummaries(projectIds);
        Map<UUID, Long> unread = loadUnreadCounts(projectIds);
        Map<UUID, StageFlags> flags = loadStageFlags(projectIds);
        List<ProjectResponse> all = projects.stream()
                .map(p -> toResponse(p, summaries.get(p.getId()), unread.getOrDefault(p.getId(), 0L),
                        flags.get(p.getId())))
                .toList();
        return stage == null ? all : all.stream().filter(r -> r.stage() == stage).toList();
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
        applyShoppingListArchive(project, status);
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
        // Three tables hold files, not one (B-26): the gallery, the object's own till receipts
        // (V129) and the receipts frozen into this object's acts (V110). The last two are the ones
        // that matter most — a photographed receipt is financial personal data, and it used to
        // survive the object it belonged to.
        List<String> blobKeys = Stream.of(
                        photoRepository.findByProjectIdOrderByCreatedAtDesc(id).stream()
                                .map(ProjectPhoto::getStorageKey),
                        projectReceiptRepository.findStorageKeysByProjectId(id).stream(),
                        workActReceiptRepository.findStorageKeysByProjectId(id).stream())
                .flatMap(s -> s)
                .filter(k -> k != null && !k.isBlank())
                .toList();

        // The shopping rows go first. They hang off the list (CASCADE) and off `estimates` (SET
        // NULL) — two SIBLING branches of this one cascade, and Postgres does not define which
        // fires first, so the SET NULL can reach a row whose list is still there and fail
        // shopping_list_item_calculated_source_check. The object is going anyway.
        shoppingListItemRepository.deleteByProjectId(id);

        projectRepository.delete(project);
        // Estimates and items are cascaded by the FK ON DELETE CASCADE.

        // Fail-soft and AFTER the commit (B-25): a storage hiccup must not roll back a delete the
        // master already confirmed — a leftover object is recoverable, a half-deleted project is
        // not — and files must not go while the rows that point at them might still come back.
        cleanup.afterCommit(blobKeys);
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
    /**
     * A finished or cancelled object archives its shopping list; reopening it brings the list back.
     * The list is never deleted — the project rule is to limit what gets CREATED, never to take
     * away access to what already exists. Archiving only removes the home-screen card; the content
     * stays reachable from the object.
     *
     * <p>The repository is used directly rather than {@code ShoppingListService} because that
     * service depends on this one for ownership, and a bean cycle for one bulk update is not worth
     * it. This is the single object-status hook — nothing else may set {@code archived_at}.</p>
     */
    private void applyShoppingListArchive(Project project, ProjectStatus newStatus) {
        boolean terminal = newStatus == ProjectStatus.COMPLETED || newStatus == ProjectStatus.CANCELLED;
        shoppingListRepository.updateArchivedAt(project.getId(), terminal ? Instant.now() : null);
    }

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
        StageFlags flags = loadStageFlags(List.of(project.getId())).get(project.getId());
        return toResponse(project, summary, unread, flags);
    }

    private static ProjectResponse toResponse(Project project, EstimateSummary summary, long unreadQuestions,
                                              StageFlags flags) {
        boolean hasSigned = flags != null && flags.hasSigned();
        boolean hasSent = flags != null && flags.hasSent();
        return summary == null
                ? ProjectResponse.from(project, null, null, unreadQuestions, hasSigned, hasSent)
                : ProjectResponse.from(project, summary.total(), summary.status(), unreadQuestions, hasSigned, hasSent);
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

    private Map<UUID, StageFlags> loadStageFlags(Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, StageFlags> result = new HashMap<>();
        for (Object[] row : projectRepository.findStageFlags(projectIds)) {
            UUID projectId = (UUID) row[0];
            boolean hasSigned = Boolean.TRUE.equals(row[1]);
            boolean hasSent = Boolean.TRUE.equals(row[2]);
            result.put(projectId, new StageFlags(hasSigned, hasSent));
        }
        return result;
    }

    private record EstimateSummary(BigDecimal total, EstimateStatus status) {}

    /** The two facts {@link ObjectStage#derive} needs beyond the {@code Project} row itself. A
     *  project absent from {@link #loadStageFlags}' result (no estimates at all) has neither. */
    private record StageFlags(boolean hasSigned, boolean hasSent) {}

    private static String normalize(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
