package com.majstr.backend.service;

import com.majstr.backend.config.PortalProperties;
import com.majstr.backend.dto.PortalStateResponse;
import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectShareLink;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ClientEmailMissingException;
import com.majstr.backend.exception.EmailNotVerifiedException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectShareLinkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProjectPortalServiceTest {

    @Mock ProjectShareLinkRepository linkRepository;
    @Mock EstimateRepository estimateRepository;
    @Mock ProjectService projectService;
    @Mock FeatureGuard featureGuard;
    @Mock PortalProperties portalProperties;
    @Mock EmailService emailService;
    @InjectMocks ProjectPortalService portalService;

    private final UUID projectId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    private Project project(boolean emailVerified, String clientEmail) {
        User owner = User.builder().id(ownerId).plan(Plan.FREE).emailVerified(emailVerified)
                .companyName("ФОП Іван").fullName("Іван").build();
        Client client = clientEmail == null
                ? null
                : Client.builder().id(UUID.randomUUID()).fullName("Олена").email(clientEmail).build();
        return Project.builder().id(projectId).owner(owner).client(client).name("Квартира").build();
    }

    private Estimate estimate(Project p, EstimateStatus status, boolean visible) {
        Estimate e = Estimate.builder()
                .id(UUID.randomUUID())
                .project(p)
                .status(status)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        e.setPortalVisible(visible);
        return e;
    }

    @Test
    void update_setsExactVisibleSet_flipsNewDraftsToSent_andMintsOneLink() {
        Project p = project(true, null);
        Estimate wanted = estimate(p, EstimateStatus.DRAFT, false);
        Estimate other = estimate(p, EstimateStatus.SENT, true);
        given(projectService.loadOwned(projectId, ownerId)).willReturn(p);
        given(estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                .willReturn(List.of(wanted, other));
        given(linkRepository.findFirstByProjectIdAndRevokedFalseOrderByCreatedAtDesc(projectId))
                .willReturn(Optional.empty());
        given(linkRepository.save(any(ProjectShareLink.class))).willAnswer(inv -> inv.getArgument(0));
        given(portalProperties.publicBaseUrl()).willReturn("https://majstr.pro");

        PortalStateResponse state = portalService.update(projectId, List.of(wanted.getId()), ownerId);

        assertThat(wanted.isPortalVisible()).isTrue();
        assertThat(wanted.getStatus()).isEqualTo(EstimateStatus.SENT); // DRAFT flipped on publish
        assertThat(other.isPortalVisible()).isFalse();                 // unticked → hidden
        assertThat(state.url()).startsWith("https://majstr.pro/portal/index.html?p=");
        verify(linkRepository).save(any(ProjectShareLink.class));
    }

    @Test
    void update_reusesTheExistingActiveLink() {
        Project p = project(true, null);
        given(projectService.loadOwned(projectId, ownerId)).willReturn(p);
        given(estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).willReturn(List.of());
        given(linkRepository.findFirstByProjectIdAndRevokedFalseOrderByCreatedAtDesc(projectId))
                .willReturn(Optional.of(ProjectShareLink.builder()
                        .id(UUID.randomUUID()).project(p).token("existing-token")
                        .createdAt(Instant.now()).revoked(false).build()));
        given(portalProperties.publicBaseUrl()).willReturn("https://majstr.pro");

        PortalStateResponse state = portalService.update(projectId, List.of(), ownerId);

        assertThat(state.url()).endsWith("?p=existing-token");
        verify(linkRepository, never()).save(any());
    }

    @Test
    void update_rejectsAForeignEstimateId() {
        Project p = project(true, null);
        given(projectService.loadOwned(projectId, ownerId)).willReturn(p);
        given(estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).willReturn(List.of());

        assertThatThrownBy(() -> portalService.update(projectId, List.of(UUID.randomUUID()), ownerId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_requiresVerifiedEmail() {
        Project p = project(false, null);
        given(projectService.loadOwned(projectId, ownerId)).willReturn(p);

        assertThatThrownBy(() -> portalService.update(projectId, List.of(), ownerId))
                .isInstanceOf(EmailNotVerifiedException.class);
    }

    @Test
    void sendEmail_requiresAClientEmail() {
        Project p = project(true, null);
        given(projectService.loadOwned(projectId, ownerId)).willReturn(p);

        assertThatThrownBy(() -> portalService.sendEmail(projectId, ownerId))
                .isInstanceOf(ClientEmailMissingException.class);
    }

    @Test
    void sendEmail_sendsThePortalUrlToTheClient() {
        Project p = project(true, "olena@x.ua");
        given(projectService.loadOwned(projectId, ownerId)).willReturn(p);
        given(linkRepository.findFirstByProjectIdAndRevokedFalseOrderByCreatedAtDesc(projectId))
                .willReturn(Optional.of(ProjectShareLink.builder()
                        .id(UUID.randomUUID()).project(p).token("tok")
                        .createdAt(Instant.now()).revoked(false).build()));
        given(estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).willReturn(List.of());
        given(portalProperties.publicBaseUrl()).willReturn("https://majstr.pro");

        portalService.sendEmail(projectId, ownerId);

        verify(emailService).sendEstimateShareEmail(
                eq("olena@x.ua"), eq("Олена"), eq("ФОП Іван"), eq("Квартира"),
                eq("https://majstr.pro/portal/index.html?p=tok"));
    }

    @Test
    void state_returnsNullUrlUntilPublished() {
        Project p = project(true, null);
        given(projectService.loadOwned(projectId, ownerId)).willReturn(p);
        given(linkRepository.findFirstByProjectIdAndRevokedFalseOrderByCreatedAtDesc(projectId))
                .willReturn(Optional.empty());
        Estimate e = estimate(p, EstimateStatus.DRAFT, false);
        given(estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).willReturn(List.of(e));

        PortalStateResponse state = portalService.state(projectId, ownerId);

        assertThat(state.url()).isNull();
        assertThat(state.estimates()).hasSize(1);
        assertThat(state.estimates().get(0).visible()).isFalse();
    }
}
