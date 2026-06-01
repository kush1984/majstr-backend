package com.majstr.backend.service;

import com.majstr.backend.config.PortalProperties;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateShareLink;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.User;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ShareLinkServiceTest {

    @Mock EstimateShareLinkRepository repository;
    @Mock EstimateService estimateService;
    @Mock FeatureGuard featureGuard;
    @Mock PortalProperties portalProperties;
    @InjectMocks ShareLinkService shareLinkService;

    private final UUID estimateId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    private Estimate estimateWithStatus(EstimateStatus status) {
        User owner = User.builder().id(ownerId).plan(Plan.FREE).build();
        Project project = Project.builder().id(UUID.randomUUID()).owner(owner).build();
        return Estimate.builder().id(estimateId).project(project).status(status).build();
    }

    @Test
    void create_flipsDraftToSent() {
        Estimate estimate = estimateWithStatus(EstimateStatus.DRAFT);
        given(estimateService.loadOwned(estimateId, ownerId)).willReturn(estimate);
        given(repository.save(any(EstimateShareLink.class))).willAnswer(inv -> inv.getArgument(0));
        given(portalProperties.publicBaseUrl()).willReturn("https://app.test");

        shareLinkService.create(estimateId, ownerId);

        assertThat(estimate.getStatus()).isEqualTo(EstimateStatus.SENT);
    }

    @Test
    void create_leavesSignedUntouched() {
        Estimate estimate = estimateWithStatus(EstimateStatus.SIGNED);
        given(estimateService.loadOwned(estimateId, ownerId)).willReturn(estimate);
        given(repository.save(any(EstimateShareLink.class))).willAnswer(inv -> inv.getArgument(0));
        given(portalProperties.publicBaseUrl()).willReturn("https://app.test");

        shareLinkService.create(estimateId, ownerId);

        assertThat(estimate.getStatus()).isEqualTo(EstimateStatus.SIGNED);
    }
}
