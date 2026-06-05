package com.majstr.backend.service;

import com.majstr.backend.dto.QuestionView;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateQuestion;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.EstimateQuestionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class QuestionServiceTest {

    @Mock EstimateQuestionRepository questionRepository;
    @Mock ProjectService projectService;
    @InjectMocks QuestionService questionService;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    private EstimateQuestion question(UUID projId, boolean read) {
        Project p = Project.builder()
                .id(projId)
                .owner(User.builder().id(ownerId).build())
                .name("P").address("A")
                .status(ProjectStatus.IN_PROGRESS)
                .build();
        Estimate e = Estimate.builder().id(UUID.randomUUID()).project(p).status(EstimateStatus.SENT).build();
        return EstimateQuestion.builder()
                .id(UUID.randomUUID())
                .estimate(e)
                .authorName("Олена")
                .authorPhone("+380671234567")
                .message("Чи можна перенести початок?")
                .read(read)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void list_returnsMappedQuestionsForOwnedProject() {
        EstimateQuestion q = question(projectId, false);
        given(questionRepository.findByEstimateProjectIdOrderByCreatedAtDesc(projectId)).willReturn(List.of(q));

        List<QuestionView> result = questionService.listForProject(projectId, ownerId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).message()).isEqualTo("Чи можна перенести початок?");
        assertThat(result.get(0).authorName()).isEqualTo("Олена");
        assertThat(result.get(0).isRead()).isFalse();
        verify(projectService).loadOwned(projectId, ownerId);
    }

    @Test
    void list_foreignProject_propagatesAccessDeniedAndReadsNothing() {
        given(projectService.loadOwned(projectId, ownerId))
                .willThrow(new AccessDeniedException("not yours"));

        assertThatThrownBy(() -> questionService.listForProject(projectId, ownerId))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(questionRepository);
    }

    @Test
    void markRead_flipsFlagAndReturnsView() {
        EstimateQuestion q = question(projectId, false);
        given(questionRepository.findById(q.getId())).willReturn(Optional.of(q));

        QuestionView view = questionService.markRead(projectId, q.getId(), ownerId);

        assertThat(q.isRead()).isTrue();
        assertThat(view.isRead()).isTrue();
        verify(projectService).loadOwned(projectId, ownerId);
    }

    @Test
    void markRead_questionUnderAnotherProject_throwsNotFoundAndLeavesFlag() {
        // Question belongs to a different project than the one in the path.
        EstimateQuestion q = question(UUID.randomUUID(), false);
        given(questionRepository.findById(q.getId())).willReturn(Optional.of(q));

        assertThatThrownBy(() -> questionService.markRead(projectId, q.getId(), ownerId))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(q.isRead()).isFalse();
    }
}
