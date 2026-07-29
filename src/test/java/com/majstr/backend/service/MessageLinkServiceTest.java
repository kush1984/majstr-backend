package com.majstr.backend.service;

import com.majstr.backend.config.PortalProperties;
import com.majstr.backend.dto.MessageLinkRequest;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectMessage;
import com.majstr.backend.entity.ProjectShareLink;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.ShareLinkKind;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.push.PushService;
import com.majstr.backend.repository.ProjectMessageRepository;
import com.majstr.backend.repository.ProjectShareLinkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The master's message link.
 *
 * <p>The assertion that matters most is the negative one: a MESSAGE token must be looked up as a
 * MESSAGE. The two kinds share a table, so a lookup by token alone would let the form's URL open the
 * portal — handing a supplier the client's prices, which is the exact thing this link exists to avoid.
 * The type system cannot express that, so it is pinned here.</p>
 */
@ExtendWith(MockitoExtension.class)
class MessageLinkServiceTest {

    @Mock ProjectShareLinkRepository linkRepository;
    @Mock ProjectMessageRepository messageRepository;
    @Mock ProjectService projectService;
    @Mock PushService pushService;
    @Mock MessageFileService messageFileService;

    private final UUID projectId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    private MessageLinkService service() {
        PortalProperties portal = new PortalProperties("https://api.majstr.pro");
        return new MessageLinkService(linkRepository, messageRepository, projectService, portal,
                pushService, messageFileService);
    }

    @Test
    void state_mintsAMessageLinkOnFirstAskAndBuildsTheFormUrl() {
        given(projectService.loadOwned(projectId, ownerId)).willReturn(project());
        given(linkRepository.findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(
                projectId, ShareLinkKind.MESSAGE)).willReturn(Optional.empty());
        given(linkRepository.save(any(ProjectShareLink.class))).willAnswer(i -> i.getArgument(0));

        String url = service().state(projectId, ownerId).url();

        ArgumentCaptor<ProjectShareLink> saved = ArgumentCaptor.forClass(ProjectShareLink.class);
        verify(linkRepository).save(saved.capture());
        assertThat(saved.getValue().getKind())
                .as("нове посилання мусить бути MESSAGE, а не портальним")
                .isEqualTo(ShareLinkKind.MESSAGE);
        assertThat(url).startsWith("https://api.majstr.pro/message/index.html?m=");
    }

    @Test
    void state_reusesTheLiveLinkRatherThanMintingASecond() {
        // A master who already sent the URL by Viber must not have it silently replaced next time they
        // open the screen — the copy in that chat has to keep working.
        ProjectShareLink existing = link("tok-live");
        given(projectService.loadOwned(projectId, ownerId)).willReturn(project());
        given(linkRepository.findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(
                projectId, ShareLinkKind.MESSAGE)).willReturn(Optional.of(existing));

        assertThat(service().state(projectId, ownerId).url()).endsWith("tok-live");
        verify(linkRepository, never()).save(any());
    }

    @Test
    void info_looksTheTokenUpAsAMessageLink_neverByTokenAlone() {
        // If this ever calls findByToken(…), a portal token would resolve here and — worse — a message
        // token would resolve in the portal, showing prices to whoever got the form.
        given(linkRepository.findByTokenAndKind("tok", ShareLinkKind.MESSAGE))
                .willReturn(Optional.of(link("tok")));

        var info = service().info("tok");

        assertThat(info.projectName()).isEqualTo("Квартира");
        assertThat(info.contractorName()).isEqualTo("ФОП Іван");
    }

    @Test
    void info_404sOnAPortalToken() {
        // The same string as a PORTAL link: the message form must not open on it.
        given(linkRepository.findByTokenAndKind("portal-tok", ShareLinkKind.MESSAGE))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service().info("portal-tok"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void submit_storesTheMessageOnTheObjectWithNoEstimate() {
        given(linkRepository.findByTokenAndKind("tok", ShareLinkKind.MESSAGE))
                .willReturn(Optional.of(link("tok")));
        given(messageRepository.save(any(ProjectMessage.class))).willAnswer(i -> {
            ProjectMessage m = i.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });

        service().submit("tok", new MessageLinkRequest("Постачальник", " +380 67 000 ", " Рахунок "),
                List.of(), "1.2.3.4");

        ArgumentCaptor<ProjectMessage> saved = ArgumentCaptor.forClass(ProjectMessage.class);
        verify(messageRepository).save(saved.capture());
        ProjectMessage m = saved.getValue();
        assertThat(m.getProject().getId()).isEqualTo(projectId);
        assertThat(m.getEstimate()).as("нікто не дивився кошторис — його тут і немає").isNull();
        assertThat(m.getAuthorName()).isEqualTo("Постачальник");
        assertThat(m.getMessage()).as("обрізається").isEqualTo("Рахунок");
        assertThat(m.getAuthorPhone()).isEqualTo("+380 67 000");
        assertThat(m.getAuthorIp()).as("для розбору зловживань").isEqualTo("1.2.3.4");
    }

    @Test
    void submit_keepsTheMessageWhenThePushFails() {
        // Fail-soft, as the portal's question already is: a notification that does not go out must not
        // lose the message that is already saved.
        given(linkRepository.findByTokenAndKind("tok", ShareLinkKind.MESSAGE))
                .willReturn(Optional.of(link("tok")));
        given(messageRepository.save(any(ProjectMessage.class))).willAnswer(i -> {
            ProjectMessage m = i.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
        willThrow(new RuntimeException("no subscription"))
                .given(pushService).sendToUser(any(), any(), any(), any());

        assertThatCode(() -> service()
                .submit("tok", new MessageLinkRequest("Ім'я", null, "Текст"), List.of(), "1.2.3.4"))
                .doesNotThrowAnyException();
        verify(messageRepository).save(any(ProjectMessage.class));
    }

    @Test
    void revoke_marksTheLiveMessageLinkRevoked() {
        ProjectShareLink existing = link("tok");
        given(linkRepository.findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(
                projectId, ShareLinkKind.MESSAGE)).willReturn(Optional.of(existing));

        service().revoke(projectId, ownerId);

        assertThat(existing.isRevoked()).isTrue();
        verify(projectService).loadOwned(eq(projectId), eq(ownerId));
    }

    private Project project() {
        return Project.builder()
                .id(projectId)
                .owner(User.builder().id(ownerId).companyName("ФОП Іван").fullName("Іван").build())
                .name("Квартира").address("вул. 1")
                .status(ProjectStatus.IN_PROGRESS)
                .build();
    }

    private ProjectShareLink link(String token) {
        return ProjectShareLink.builder()
                .id(UUID.randomUUID())
                .project(project())
                .token(token)
                .kind(ShareLinkKind.MESSAGE)
                .build();
    }
}
