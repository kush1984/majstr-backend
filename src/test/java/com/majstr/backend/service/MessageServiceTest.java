package com.majstr.backend.service;

import com.majstr.backend.dto.MessageView;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.ProjectMessage;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.ProjectMessageRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock ProjectMessageRepository messageRepository;
    @Mock ProjectService projectService;
    @InjectMocks MessageService messageService;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    private ProjectMessage question(UUID projId, boolean read) {
        Project p = Project.builder()
                .id(projId)
                .owner(User.builder().id(ownerId).build())
                .name("P").address("A")
                .status(ProjectStatus.IN_PROGRESS)
                .build();
        Estimate e = Estimate.builder().id(UUID.randomUUID()).project(p).status(EstimateStatus.SENT).build();
        return ProjectMessage.builder()
                .id(UUID.randomUUID())
                // The object, not the estimate, is what a message belongs to since V74. This fixture
                // had the same blind spot the production builder did: setting only the estimate
                // compiles, and the miss surfaces as an NPE rather than as a type error.
                .project(p)
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
        ProjectMessage q = question(projectId, false);
        given(messageRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).willReturn(List.of(q));

        List<MessageView> result = messageService.listForProject(projectId, ownerId);

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

        assertThatThrownBy(() -> messageService.listForProject(projectId, ownerId))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(messageRepository);
    }

    @Test
    void markRead_flipsFlagAndReturnsView() {
        ProjectMessage q = question(projectId, false);
        given(messageRepository.findById(q.getId())).willReturn(Optional.of(q));

        MessageView view = messageService.markRead(projectId, q.getId(), ownerId);

        assertThat(q.isRead()).isTrue();
        assertThat(view.isRead()).isTrue();
        verify(projectService).loadOwned(projectId, ownerId);
    }

    @Test
    void markRead_questionUnderAnotherProject_throwsNotFoundAndLeavesFlag() {
        // Question belongs to a different project than the one in the path.
        ProjectMessage q = question(UUID.randomUUID(), false);
        given(messageRepository.findById(q.getId())).willReturn(Optional.of(q));

        assertThatThrownBy(() -> messageService.markRead(projectId, q.getId(), ownerId))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(q.isRead()).isFalse();
    }

    @Test
    void listForProject_rendersAMessageThatHasNoEstimate() {
        // What arrives through the master's message link. Before V74 the column was NOT NULL, and
        // MessageView.from dereferenced the estimate without checking — so this is both the new
        // storage shape and the null it now has to survive.
        Project p = Project.builder()
                .id(projectId)
                .owner(User.builder().id(ownerId).build())
                .name("P").address("A")
                .status(ProjectStatus.IN_PROGRESS)
                .build();
        ProjectMessage linkMessage = ProjectMessage.builder()
                .id(UUID.randomUUID())
                .project(p)
                .authorName("Постачальник")
                .message("Рахунок у вкладенні")
                .read(false)
                .createdAt(Instant.now())
                .build();
        given(messageRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                .willReturn(List.of(linkMessage));

        List<MessageView> result = messageService.listForProject(projectId, ownerId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().estimateName()).isNull();
        assertThat(result.getFirst().message()).isEqualTo("Рахунок у вкладенні");
    }

    @Test
    void delete_removesTheMessage_andIsIdempotent() {
        ProjectMessage q = question(projectId, false);
        given(messageRepository.findById(q.getId())).willReturn(Optional.of(q), Optional.empty());

        messageService.delete(projectId, q.getId(), ownerId);
        messageService.delete(projectId, q.getId(), ownerId);   // must not throw

        verify(messageRepository).delete(q);
    }

    @Test
    void delete_leavesAMessageBelongingToAnotherProjectAlone() {
        ProjectMessage foreign = question(UUID.randomUUID(), false);
        given(messageRepository.findById(foreign.getId())).willReturn(Optional.of(foreign));

        messageService.delete(projectId, foreign.getId(), ownerId);

        verify(messageRepository, never()).delete(foreign);
    }
}