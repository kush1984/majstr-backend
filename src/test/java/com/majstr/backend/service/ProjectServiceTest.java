package com.majstr.backend.service;

import com.majstr.backend.dto.ProjectRequest;
import com.majstr.backend.dto.ProjectResponse;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.User;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock ProjectRepository projectRepository;
    @Mock EstimateRepository estimateRepository;
    @Mock UserRepository userRepository;
    @Mock ClientService clientService;
    @Mock com.majstr.backend.feature.LimitService limitService;
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
    void updateStatus_foreignProject_throwsAccessDenied() {
        Project p = owned(ProjectStatus.IN_PROGRESS, null); // owned by ownerId
        given(projectRepository.findById(projectId)).willReturn(Optional.of(p));

        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> projectService.updateStatus(projectId, ProjectStatus.COMPLETED, stranger))
                .isInstanceOf(AccessDeniedException.class);
    }
}
