package com.majstr.backend.service;

import com.majstr.backend.config.PortalProperties;
import com.majstr.backend.dto.ActShareStateResponse;
import com.majstr.backend.dto.PortalStateResponse;
import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ShareLinkKind;
import com.majstr.backend.entity.ProjectShareLink;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.exception.ClientEmailMissingException;
import com.majstr.backend.exception.EmailNotVerifiedException;
import com.majstr.backend.exception.InvalidEstimateStatusException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectShareLinkRepository;
import com.majstr.backend.exception.WorkActValidationException;
import com.majstr.backend.repository.WorkActItemRepository;
import com.majstr.backend.repository.WorkActRepository;
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
    @Mock WorkActRepository workActRepository;
    @Mock WorkActItemRepository workActItemRepository;
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

    private Estimate economyEstimate(Project p, EstimateStatus status, boolean visible) {
        Estimate e = Estimate.builder()
                .id(UUID.randomUUID())
                .project(p)
                .status(status)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        e.setEconomyVisible(visible);
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
        given(linkRepository.findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(
                projectId, ShareLinkKind.PORTAL))
                .willReturn(Optional.empty());
        given(linkRepository.save(any(ProjectShareLink.class))).willAnswer(inv -> inv.getArgument(0));
        given(portalProperties.publicBaseUrl()).willReturn("https://majstr.pro");

        PortalStateResponse state = portalService.update(projectId, List.of(wanted.getId()), ownerId);

        assertThat(wanted.isPortalVisible()).isTrue();
        assertThat(wanted.getStatus()).isEqualTo(EstimateStatus.SENT); // DRAFT flipped on publish
        assertThat(other.isPortalVisible()).isFalse();                 // unticked → hidden
        assertThat(state.url()).startsWith("https://majstr.pro/portal/index.html?p=");
        assertThat(state.paymentsVisible()).isFalse(); // SIGNATURE portal never has a payments card
        verify(linkRepository).save(any(ProjectShareLink.class));
    }

    @Test
    void update_coercesASignedEstimateToNeverBePortalVisible_evenIfRequested() {
        // A SIGNED estimate has "moved" to ECONOMY — the SIGNATURE portal must never show one,
        // regardless of what the caller asked for (defense-in-depth; the picker never legitimately
        // offers a SIGNED id to tick, but a stale client request must still be refused server-side).
        Project p = project(true, null);
        Estimate signed = estimate(p, EstimateStatus.SIGNED, false);
        given(projectService.loadOwned(projectId, ownerId)).willReturn(p);
        given(estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                .willReturn(List.of(signed));
        given(linkRepository.findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(
                projectId, ShareLinkKind.PORTAL))
                .willReturn(Optional.empty());
        given(linkRepository.save(any(ProjectShareLink.class))).willAnswer(inv -> inv.getArgument(0));
        given(portalProperties.publicBaseUrl()).willReturn("https://majstr.pro");

        PortalStateResponse state = portalService.update(projectId, List.of(signed.getId()), ownerId);

        assertThat(signed.isPortalVisible()).isFalse();
        assertThat(state.estimates().get(0).visible()).isFalse();
    }

    @Test
    void state_masksASignedEstimateAsNeverVisible_evenIfTheStoredFlagIsStillTrue() {
        // Self-healing: a link published BEFORE signing leaves portalVisible=true stored on a now-
        // SIGNED estimate (nothing clears it on sign). state() must mask it so the picker's ticked
        // set — seeded from this — drops the id, and the next publish clears the stale flag for real.
        Project p = project(true, null);
        Estimate signed = estimate(p, EstimateStatus.SIGNED, true);
        given(projectService.loadOwned(projectId, ownerId)).willReturn(p);
        given(linkRepository.findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(
                projectId, ShareLinkKind.PORTAL))
                .willReturn(Optional.empty());
        given(estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).willReturn(List.of(signed));

        PortalStateResponse state = portalService.state(projectId, ownerId);

        assertThat(state.estimates().get(0).visible()).isFalse();
    }

    @Test
    void update_reusesTheExistingActiveLink() {
        Project p = project(true, null);
        given(projectService.loadOwned(projectId, ownerId)).willReturn(p);
        given(estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).willReturn(List.of());
        given(linkRepository.findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(
                projectId, ShareLinkKind.PORTAL))
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
    void updateEconomy_setsExactVisibleSetAndPaymentsVisible_mintsOneLink() {
        Project p = project(true, null);
        Estimate wanted = economyEstimate(p, EstimateStatus.SIGNED, false);
        Estimate other = economyEstimate(p, EstimateStatus.SIGNED, true);
        given(projectService.loadOwned(projectId, ownerId)).willReturn(p);
        given(estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                .willReturn(List.of(wanted, other));
        given(linkRepository.findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(
                projectId, ShareLinkKind.ECONOMY))
                .willReturn(Optional.empty());
        given(linkRepository.save(any(ProjectShareLink.class))).willAnswer(inv -> inv.getArgument(0));
        given(portalProperties.publicBaseUrl()).willReturn("https://majstr.pro");

        PortalStateResponse state = portalService.updateEconomy(
                projectId, List.of(wanted.getId()), true, ownerId);

        assertThat(wanted.isEconomyVisible()).isTrue();
        assertThat(other.isEconomyVisible()).isFalse();
        assertThat(state.url()).startsWith("https://majstr.pro/portal/index.html?e=");
        assertThat(state.paymentsVisible()).isTrue();
        verify(linkRepository).save(any(ProjectShareLink.class));
    }

    @Test
    void updateEconomy_rejectsANonSignedEstimate() {
        Project p = project(true, null);
        Estimate draft = economyEstimate(p, EstimateStatus.DRAFT, false);
        given(projectService.loadOwned(projectId, ownerId)).willReturn(p);
        given(estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                .willReturn(List.of(draft));

        assertThatThrownBy(() -> portalService.updateEconomy(
                projectId, List.of(draft.getId()), false, ownerId))
                .isInstanceOf(InvalidEstimateStatusException.class);
    }

    @Test
    void economyState_returnsNullUrlUntilPublished() {
        Project p = project(true, null);
        given(projectService.loadOwned(projectId, ownerId)).willReturn(p);
        given(linkRepository.findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(
                projectId, ShareLinkKind.ECONOMY))
                .willReturn(Optional.empty());
        Estimate e = economyEstimate(p, EstimateStatus.SIGNED, false);
        given(estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).willReturn(List.of(e));

        PortalStateResponse state = portalService.economyState(projectId, ownerId);

        assertThat(state.url()).isNull();
        assertThat(state.estimates()).hasSize(1);
        assertThat(state.estimates().get(0).visible()).isFalse();
    }

    @Test
    void sendEconomyEmail_sendsTheEconomyUrlToTheClient() {
        Project p = project(true, "olena@x.ua");
        given(projectService.loadOwned(projectId, ownerId)).willReturn(p);
        given(linkRepository.findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(
                projectId, ShareLinkKind.ECONOMY))
                .willReturn(Optional.of(ProjectShareLink.builder()
                        .id(UUID.randomUUID()).project(p).token("tok")
                        .createdAt(Instant.now()).revoked(false).build()));
        given(estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).willReturn(List.of());
        given(portalProperties.publicBaseUrl()).willReturn("https://majstr.pro");

        portalService.sendEconomyEmail(projectId, ownerId);

        verify(emailService).sendEstimateShareEmail(
                eq("olena@x.ua"), eq("Олена"), eq("ФОП Іван"), eq("Квартира"),
                eq("https://majstr.pro/portal/index.html?e=tok"));
    }

    @Test
    void sendEmail_requiresAClientEmail() {
        Project p = project(true, null);
        given(projectService.loadOwned(projectId, ownerId)).willReturn(p);
        given(linkRepository.findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(
                projectId, ShareLinkKind.PORTAL))
                .willReturn(Optional.of(ProjectShareLink.builder()
                        .id(UUID.randomUUID()).project(p).token("tok")
                        .createdAt(Instant.now()).revoked(false).build()));
        given(portalProperties.publicBaseUrl()).willReturn("https://majstr.pro");

        assertThatThrownBy(() -> portalService.sendEmail(projectId, ownerId))
                .isInstanceOf(ClientEmailMissingException.class);
    }

    @Test
    void sendEmail_sendsThePortalUrlToTheClient() {
        Project p = project(true, "olena@x.ua");
        given(projectService.loadOwned(projectId, ownerId)).willReturn(p);
        given(linkRepository.findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(
                projectId, ShareLinkKind.PORTAL))
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
        given(linkRepository.findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(
                projectId, ShareLinkKind.PORTAL))
                .willReturn(Optional.empty());
        Estimate e = estimate(p, EstimateStatus.DRAFT, false);
        given(estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).willReturn(List.of(e));

        PortalStateResponse state = portalService.state(projectId, ownerId);

        assertThat(state.url()).isNull();
        assertThat(state.estimates()).hasSize(1);
        assertThat(state.estimates().get(0).visible()).isFalse();
    }

    // ---- ACT share (prompt 5) ----------------------------------------------

    private WorkAct act(Project p, WorkActStatus status) {
        return WorkAct.builder().id(UUID.randomUUID()).userId(ownerId).project(p)
                .number("7").kind(WorkActKind.INTERIM).status(status)
                .issuedAt(java.time.LocalDate.now()).periodFrom(java.time.LocalDate.now())
                .periodTo(java.time.LocalDate.now()).build();
    }

    @Test
    void updateAct_flipsDraftToSent_andMintsTheActLink() {
        Project p = project(true, null);
        WorkAct a = act(p, WorkActStatus.DRAFT);
        given(workActRepository.findByIdAndUserId(a.getId(), ownerId)).willReturn(Optional.of(a));
        given(workActItemRepository.existsByWorkActId(a.getId())).willReturn(true);
        given(linkRepository.findFirstByWorkActIdAndRevokedFalseOrderByCreatedAtDesc(a.getId()))
                .willReturn(Optional.empty());
        given(linkRepository.save(any(ProjectShareLink.class))).willAnswer(inv -> inv.getArgument(0));
        given(portalProperties.publicBaseUrl()).willReturn("https://majstr.pro");

        ActShareStateResponse state = portalService.updateAct(a.getId(), ownerId);

        assertThat(a.getStatus()).isEqualTo(WorkActStatus.SENT);
        assertThat(a.getSentAt()).isNotNull();
        assertThat(state.url()).contains("?a=");
        assertThat(state.shared()).isTrue();
    }

    @Test
    void updateAct_rejectedAct_cannotBeShared() {
        Project p = project(true, null);
        WorkAct a = act(p, WorkActStatus.REJECTED);
        given(workActRepository.findByIdAndUserId(a.getId(), ownerId)).willReturn(Optional.of(a));

        assertThatThrownBy(() -> portalService.updateAct(a.getId(), ownerId))
                .isInstanceOf(InvalidEstimateStatusException.class);
        verify(linkRepository, never()).save(any());
    }

    @Test
    void updateAct_emptyDraft_isRefused() {
        // An empty act must never leave DRAFT (review fix): once SENT the client could sign it, and
        // a SIGNED act is immutable and undeletable — the object would carry permanent junk.
        Project p = project(true, null);
        WorkAct a = act(p, WorkActStatus.DRAFT);
        given(workActRepository.findByIdAndUserId(a.getId(), ownerId)).willReturn(Optional.of(a));
        given(workActItemRepository.existsByWorkActId(a.getId())).willReturn(false);

        assertThatThrownBy(() -> portalService.updateAct(a.getId(), ownerId))
                .isInstanceOf(WorkActValidationException.class);

        assertThat(a.getStatus()).isEqualTo(WorkActStatus.DRAFT);
        verify(linkRepository, never()).save(any());
    }
}
