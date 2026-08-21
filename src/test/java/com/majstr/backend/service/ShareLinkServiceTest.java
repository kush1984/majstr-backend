package com.majstr.backend.service;

import com.majstr.backend.config.PortalProperties;
import com.majstr.backend.dto.ShareLinkResponse;
import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateShareLink;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ClientEmailMissingException;
import com.majstr.backend.exception.EmailNotVerifiedException;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
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
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ShareLinkServiceTest {

    @Mock EstimateShareLinkRepository repository;
    @Mock EstimateService estimateService;
    @Mock FeatureGuard featureGuard;
    @Mock PortalProperties portalProperties;
    @Mock EmailService emailService;
    @InjectMocks ShareLinkService shareLinkService;

    private final UUID estimateId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    private Estimate estimateWithStatus(EstimateStatus status, boolean emailVerified) {
        User owner = User.builder().id(ownerId).plan(Plan.FREE).emailVerified(emailVerified).build();
        Project project = Project.builder().id(UUID.randomUUID()).owner(owner).build();
        return Estimate.builder().id(estimateId).project(project).status(status).build();
    }

    private Estimate estimateWithClient(String clientEmail) {
        User owner = User.builder().id(ownerId).plan(Plan.FREE).emailVerified(true)
                .companyName("ФОП Іван").fullName("Іван").build();
        Client client = Client.builder().id(UUID.randomUUID()).fullName("Олена").email(clientEmail).build();
        Project project = Project.builder().id(UUID.randomUUID()).owner(owner).client(client).name("Квартира").build();
        return Estimate.builder().id(estimateId).project(project).status(EstimateStatus.DRAFT).build();
    }

    // ---- create -----------------------------------------------------------

    @Test
    void create_flipsDraftToSent() {
        Estimate estimate = estimateWithStatus(EstimateStatus.DRAFT, true);
        given(estimateService.loadOwned(estimateId, ownerId)).willReturn(estimate);
        given(repository.save(any(EstimateShareLink.class))).willAnswer(inv -> inv.getArgument(0));
        given(portalProperties.publicBaseUrl()).willReturn("https://app.test");

        shareLinkService.create(estimateId, ownerId);

        assertThat(estimate.getStatus()).isEqualTo(EstimateStatus.SENT);
    }

    @Test
    void create_leavesSignedUntouched() {
        Estimate estimate = estimateWithStatus(EstimateStatus.SIGNED, true);
        given(estimateService.loadOwned(estimateId, ownerId)).willReturn(estimate);
        given(repository.save(any(EstimateShareLink.class))).willAnswer(inv -> inv.getArgument(0));
        given(portalProperties.publicBaseUrl()).willReturn("https://app.test");

        shareLinkService.create(estimateId, ownerId);

        assertThat(estimate.getStatus()).isEqualTo(EstimateStatus.SIGNED);
    }

    @Test
    void create_reusesTheEstimatesExistingUsableLink() {
        // Tapping "поділитися" twice must hand back the SAME URL — a second mint would leave the
        // first token live with nobody tracking it.
        Estimate estimate = estimateWithStatus(EstimateStatus.SENT, true);
        EstimateShareLink existing = EstimateShareLink.builder()
                .id(UUID.randomUUID()).estimate(estimate).token("existing-token").revoked(false).build();
        given(estimateService.loadOwned(estimateId, ownerId)).willReturn(estimate);
        given(repository.findFirstByEstimateIdAndRevokedFalseOrderByCreatedAtDesc(estimateId))
                .willReturn(Optional.of(existing));
        given(portalProperties.publicBaseUrl()).willReturn("https://app.test");

        ShareLinkResponse response = shareLinkService.create(estimateId, ownerId);

        assertThat(response.url()).isEqualTo("https://app.test/portal/index.html?t=existing-token");
        verify(repository, never()).save(any(EstimateShareLink.class));
    }

    @Test
    void create_doesNotReuseAnExpiredLink() {
        // Only revoked links are filtered by the query — an expired one would otherwise be handed
        // out as a fresh share and 404 the moment the client opened it.
        Estimate estimate = estimateWithStatus(EstimateStatus.SENT, true);
        EstimateShareLink expired = EstimateShareLink.builder()
                .id(UUID.randomUUID()).estimate(estimate).token("expired-token").revoked(false)
                .expiresAt(Instant.now().minusSeconds(60)).build();
        given(estimateService.loadOwned(estimateId, ownerId)).willReturn(estimate);
        given(repository.findFirstByEstimateIdAndRevokedFalseOrderByCreatedAtDesc(estimateId))
                .willReturn(Optional.of(expired));
        given(repository.save(any(EstimateShareLink.class))).willAnswer(inv -> inv.getArgument(0));
        given(portalProperties.publicBaseUrl()).willReturn("https://app.test");

        ShareLinkResponse response = shareLinkService.create(estimateId, ownerId);

        assertThat(response.url()).doesNotContain("expired-token");
        verify(repository).save(any(EstimateShareLink.class));
    }

    @Test
    void create_blockedWhenEmailNotVerified() {
        Estimate estimate = estimateWithStatus(EstimateStatus.DRAFT, false);
        given(estimateService.loadOwned(estimateId, ownerId)).willReturn(estimate);

        assertThatThrownBy(() -> shareLinkService.create(estimateId, ownerId))
                .isInstanceOf(EmailNotVerifiedException.class);
        assertThat(estimate.getStatus()).isEqualTo(EstimateStatus.DRAFT);
    }

    // ---- sendByEmail ------------------------------------------------------

    @Test
    void sendByEmail_sendsLinkToClientAndFlipsToSent() {
        Estimate estimate = estimateWithClient("olena@example.com");
        given(estimateService.loadOwned(estimateId, ownerId)).willReturn(estimate);
        given(repository.findFirstByEstimateIdAndRevokedFalseOrderByCreatedAtDesc(estimateId))
                .willReturn(Optional.empty());
        given(repository.save(any(EstimateShareLink.class))).willAnswer(inv -> inv.getArgument(0));
        given(portalProperties.publicBaseUrl()).willReturn("https://app.test");

        shareLinkService.sendByEmail(estimateId, ownerId);

        assertThat(estimate.getStatus()).isEqualTo(EstimateStatus.SENT);
        verify(emailService).sendEstimateShareEmail(
                eq("olena@example.com"), eq("Олена"), eq("ФОП Іван"), eq("Квартира"), anyString());
    }

    @Test
    void sendByEmail_clientWithoutEmail_throwsAndSendsNothing() {
        Estimate estimate = estimateWithClient(null);
        given(estimateService.loadOwned(estimateId, ownerId)).willReturn(estimate);

        assertThatThrownBy(() -> shareLinkService.sendByEmail(estimateId, ownerId))
                .isInstanceOf(ClientEmailMissingException.class);
        assertThat(estimate.getStatus()).isEqualTo(EstimateStatus.DRAFT);
        verifyNoInteractions(emailService);
    }

    @Test
    void sendByEmail_noClientAtAll_throwsAndSendsNothing() {
        // An object created without a client (the new optional-client flow) can't be
        // emailed — same guard as a client with no email, but the null-client branch.
        Estimate estimate = estimateWithStatus(EstimateStatus.DRAFT, true);
        given(estimateService.loadOwned(estimateId, ownerId)).willReturn(estimate);

        assertThatThrownBy(() -> shareLinkService.sendByEmail(estimateId, ownerId))
                .isInstanceOf(ClientEmailMissingException.class);
        assertThat(estimate.getStatus()).isEqualTo(EstimateStatus.DRAFT);
        verifyNoInteractions(emailService);
    }
}
