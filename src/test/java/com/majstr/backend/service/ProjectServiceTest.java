package com.majstr.backend.service;

import com.majstr.backend.dto.ProjectRequest;
import com.majstr.backend.dto.ProjectResponse;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ObjectStage;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.ProjectMessageRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock ProjectRepository projectRepository;
    @Mock EstimateRepository estimateRepository;
    @Mock ProjectMessageRepository messageRepository;
    @Mock UserRepository userRepository;
    @Mock ClientService clientService;
    @Mock com.majstr.backend.feature.LimitService limitService;
    // Needed since delete() also cleans up the project's stored photos — without these
    // @InjectMocks would leave them null and delete would NPE.
    @Mock com.majstr.backend.repository.ProjectPhotoRepository photoRepository;
    @Mock com.majstr.backend.storage.StorageService storage;
    @InjectMocks ProjectService projectService;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    private Project owned(ProjectStatus status, Instant completedAt) {
        return Project.builder()
                .id(projectId)
                .owner(User.builder().id(ownerId).build())
                .name("P").address("A")
                .status(status)
                .completedAt(completedAt)
                .build();
    }

    @Test
    void updateStatus_toCompleted_stampsCompletedAt() {
        Project p = owned(ProjectStatus.IN_PROGRESS, null);
        given(projectRepository.findById(projectId)).willReturn(Optional.of(p));
        given(estimateRepository.findLatestEstimateSummaries(anyCollection())).willReturn(List.of());

        ProjectResponse r = projectService.updateStatus(projectId, ProjectStatus.COMPLETED, ownerId);

        assertThat(p.getCompletedAt()).isNotNull();
        assertThat(p.getStatus()).isEqualTo(ProjectStatus.COMPLETED);
        assertThat(r.completedAt()).isNotNull();
        assertThat(r.latestEstimateTotal()).isNull();
        assertThat(r.estimateStatus()).isNull();
    }

    @Test
    void updateStatus_completedToCompleted_keepsOriginalStamp() {
        Instant original = Instant.parse("2026-01-15T10:00:00Z");
        Project p = owned(ProjectStatus.COMPLETED, original);
        given(projectRepository.findById(projectId)).willReturn(Optional.of(p));
        given(estimateRepository.findLatestEstimateSummaries(anyCollection())).willReturn(List.of());

        projectService.updateStatus(projectId, ProjectStatus.COMPLETED, ownerId);

        assertThat(p.getCompletedAt()).isEqualTo(original);
    }

    @Test
    void updateStatus_leavingCompleted_clearsStamp() {
        Project p = owned(ProjectStatus.COMPLETED, Instant.parse("2026-01-15T10:00:00Z"));
        given(projectRepository.findById(projectId)).willReturn(Optional.of(p));
        given(estimateRepository.findLatestEstimateSummaries(anyCollection())).willReturn(List.of());

        projectService.updateStatus(projectId, ProjectStatus.IN_PROGRESS, ownerId);

        assertThat(p.getCompletedAt()).isNull();
    }

    @Test
    void update_doesNotShiftCompletedAt() {
        Instant original = Instant.parse("2026-01-15T10:00:00Z");
        Project p = owned(ProjectStatus.COMPLETED, original);
        given(projectRepository.findById(projectId)).willReturn(Optional.of(p));
        given(estimateRepository.findLatestEstimateSummaries(anyCollection())).willReturn(List.of());

        projectService.update(projectId, new ProjectRequest("New name", "New addr", null, null), ownerId);

        assertThat(p.getCompletedAt()).isEqualTo(original);
        assertThat(p.getStatus()).isEqualTo(ProjectStatus.COMPLETED);
    }

    @Test
    void get_populatesLatestEstimateSummary() {
        Project p = owned(ProjectStatus.IN_PROGRESS, null);
        given(projectRepository.findById(projectId)).willReturn(Optional.of(p));
        given(estimateRepository.findLatestEstimateSummaries(anyCollection())).willReturn(List.<Object[]>of(
                new Object[]{projectId, "SENT", new BigDecimal("1500.00")}
        ));

        ProjectResponse r = projectService.get(projectId, ownerId);

        assertThat(r.latestEstimateTotal()).isEqualByComparingTo("1500.00");
        assertThat(r.estimateStatus()).isEqualTo(EstimateStatus.SENT);
    }

    @Test
    void get_noEstimate_leavesSummaryNull() {
        Project p = owned(ProjectStatus.IN_PROGRESS, null);
        given(projectRepository.findById(projectId)).willReturn(Optional.of(p));
        given(estimateRepository.findLatestEstimateSummaries(anyCollection())).willReturn(List.of());

        ProjectResponse r = projectService.get(projectId, ownerId);

        assertThat(r.latestEstimateTotal()).isNull();
        assertThat(r.estimateStatus()).isNull();
    }

    @Test
    void list_includesUnreadQuestionCounts() {
        Project p = owned(ProjectStatus.IN_PROGRESS, null);
        given(projectRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId)).willReturn(List.of(p));
        given(estimateRepository.findLatestEstimateSummaries(anyCollection())).willReturn(List.of());
        given(messageRepository.countUnreadByProjectIds(anyCollection())).willReturn(List.<Object[]>of(
                new Object[]{projectId, 3L}
        ));

        List<ProjectResponse> list = projectService.listForOwner(ownerId, null);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).unreadQuestions()).isEqualTo(3L);
    }

    @Test
    void list_noUnreadQuestions_defaultsToZero() {
        Project p = owned(ProjectStatus.IN_PROGRESS, null);
        given(projectRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId)).willReturn(List.of(p));
        given(estimateRepository.findLatestEstimateSummaries(anyCollection())).willReturn(List.of());
        given(messageRepository.countUnreadByProjectIds(anyCollection())).willReturn(List.of());

        List<ProjectResponse> list = projectService.listForOwner(ownerId, null);

        assertThat(list.get(0).unreadQuestions()).isZero();
    }

    // ---- object-status-unification: derived stage --------------------------------------------

    @Test
    void get_computesStageFromTheSignedFlag() {
        Project p = owned(ProjectStatus.ESTIMATING, null); // raw status no longer drives display
        given(projectRepository.findById(projectId)).willReturn(Optional.of(p));
        given(estimateRepository.findLatestEstimateSummaries(anyCollection())).willReturn(List.of());
        given(projectRepository.findStageFlags(anyCollection())).willReturn(List.<Object[]>of(
                new Object[]{projectId, true, false} // has a SIGNED estimate
        ));

        ProjectResponse r = projectService.get(projectId, ownerId);

        assertThat(r.stage()).isEqualTo(ObjectStage.IN_PROGRESS);
    }

    @Test
    void get_noEstimatesAtAll_isAssessment() {
        Project p = owned(ProjectStatus.DRAFT, null);
        given(projectRepository.findById(projectId)).willReturn(Optional.of(p));
        given(estimateRepository.findLatestEstimateSummaries(anyCollection())).willReturn(List.of());
        given(projectRepository.findStageFlags(anyCollection())).willReturn(List.of()); // no row at all

        ProjectResponse r = projectService.get(projectId, ownerId);

        assertThat(r.stage()).isEqualTo(ObjectStage.ASSESSMENT);
    }

    @Test
    void list_filtersByTheDerivedStage_notTheRawProjectStatus() {
        UUID inProgressId = projectId;
        UUID assessmentId = UUID.randomUUID();
        Project inProgress = owned(ProjectStatus.ESTIMATING, null); // raw status intentionally "wrong"
        Project assessment = Project.builder()
                .id(assessmentId).owner(User.builder().id(ownerId).build())
                .name("P2").address("A").status(ProjectStatus.DRAFT).build();
        given(projectRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId))
                .willReturn(List.of(inProgress, assessment));
        given(estimateRepository.findLatestEstimateSummaries(anyCollection())).willReturn(List.of());
        given(messageRepository.countUnreadByProjectIds(anyCollection())).willReturn(List.of());
        given(projectRepository.findStageFlags(anyCollection())).willReturn(List.<Object[]>of(
                new Object[]{inProgressId, true, false}
        ));

        List<ProjectResponse> shown = projectService.listForOwner(ownerId, ObjectStage.IN_PROGRESS);

        assertThat(shown).extracting(ProjectResponse::id).containsExactly(inProgressId);
    }

    @Test
    void updateStatus_foreignProject_throwsAccessDenied() {
        Project p = owned(ProjectStatus.IN_PROGRESS, null); // owned by ownerId
        given(projectRepository.findById(projectId)).willReturn(Optional.of(p));

        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> projectService.updateStatus(projectId, ProjectStatus.COMPLETED, stranger))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- offline authoring: client-provided UUID makes create idempotent -----------------------

    @Test
    void create_withRequestedId_persistsThatId() {
        UUID requestedId = UUID.randomUUID();
        given(projectRepository.findById(requestedId)).willReturn(Optional.empty());
        given(userRepository.getReferenceById(ownerId)).willReturn(User.builder().id(ownerId).build());
        given(projectRepository.save(any(Project.class))).willAnswer(inv -> inv.getArgument(0));

        ProjectResponse r = projectService.create(
                new ProjectRequest("Хата", "вул. 1", null, null), ownerId, requestedId);

        assertThat(r.id()).isEqualTo(requestedId);
    }

    @Test
    void create_withRequestedId_alreadyExistsAndOwned_isIdempotentAndSkipsLimit() {
        UUID requestedId = UUID.randomUUID();
        Project existing = Project.builder()
                .id(requestedId)
                .owner(User.builder().id(ownerId).build())
                .name("Вже є").address("A").status(ProjectStatus.DRAFT)
                .build();
        given(projectRepository.findById(requestedId)).willReturn(Optional.of(existing));

        ProjectResponse r = projectService.create(
                new ProjectRequest("Дубль", "вул. 2", null, null), ownerId, requestedId);

        assertThat(r.id()).isEqualTo(requestedId);
        assertThat(r.name()).isEqualTo("Вже є");
        // A replay must NOT insert again and must NOT re-reserve a lifetime slot.
        org.mockito.Mockito.verify(projectRepository, org.mockito.Mockito.never()).save(any(Project.class));
        org.mockito.Mockito.verify(limitService, org.mockito.Mockito.never())
                .reserveProjectSlot(any());
    }

    @Test
    void create_withRequestedId_belongsToAnotherUser_throwsAccessDenied() {
        UUID requestedId = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        Project existing = Project.builder()
                .id(requestedId)
                .owner(User.builder().id(stranger).build())
                .name("Чужий").address("A").status(ProjectStatus.DRAFT)
                .build();
        given(projectRepository.findById(requestedId)).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> projectService.create(
                new ProjectRequest("Викрадач", "вул. 3", null, null), ownerId, requestedId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void delete_alreadyGoneIsANoOp_notA404() {
        // Idempotent: the outbox replays a delete whose response was lost, and a 404 on replay
        // is classified as a permanent rejection — the master would be told their delete
        // "wasn't saved to the cloud" when it had been.
        UUID projectId = UUID.randomUUID();
        given(projectRepository.findById(projectId)).willReturn(Optional.empty());

        assertThatCode(() -> projectService.delete(projectId, ownerId)).doesNotThrowAnyException();

        org.mockito.Mockito.verify(projectRepository, org.mockito.Mockito.never()).delete(any(Project.class));
    }

    @Test
    void delete_alsoRemovesTheProjectsStoredPhotos() throws Exception {
        // The photo ROWS cascade with the FK, but the objects in storage did not — every
        // project delete leaked its files forever. Receipt photos are financial personal
        // data, so "deleted" has to mean deleted.
        UUID projectId = UUID.randomUUID();
        Project project = Project.builder()
                .id(projectId).owner(User.builder().id(ownerId).build())
                .name("Обʼєкт").address("вул. 1").status(ProjectStatus.DRAFT).build();
        given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
        given(photoRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).willReturn(List.of(
                com.majstr.backend.entity.ProjectPhoto.builder().storageKey("photos/a.jpg").build(),
                com.majstr.backend.entity.ProjectPhoto.builder().storageKey("photos/b.jpg").build()));

        projectService.delete(projectId, ownerId);

        org.mockito.Mockito.verify(projectRepository).delete(project);
        org.mockito.Mockito.verify(storage).delete("photos/a.jpg");
        org.mockito.Mockito.verify(storage).delete("photos/b.jpg");
    }

    @Test
    void delete_survivesAStorageFailure() throws Exception {
        // Fail-soft on purpose: the row deletion is already committed by then, so throwing
        // here would leave a half-deleted project. A leftover object is recoverable.
        UUID projectId = UUID.randomUUID();
        Project project = Project.builder()
                .id(projectId).owner(User.builder().id(ownerId).build())
                .name("Обʼєкт").address("вул. 1").status(ProjectStatus.DRAFT).build();
        given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
        given(photoRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).willReturn(List.of(
                com.majstr.backend.entity.ProjectPhoto.builder().storageKey("photos/a.jpg").build()));
        org.mockito.BDDMockito.willThrow(new java.io.IOException("R2 down"))
                .given(storage).delete("photos/a.jpg");

        projectService.delete(projectId, ownerId); // must not throw

        org.mockito.Mockito.verify(projectRepository).delete(project);
    }
}
