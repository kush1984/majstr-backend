package com.majstr.backend.integration;

import com.majstr.backend.dto.ActivationFunnelResponse;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateShareLink;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectShareLink;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.ShareLinkKind;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.ProjectShareLinkRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.MetricsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The funnel's {@code shared} step, as real SQL against real Postgres.
 *
 * <p><b>Redefined 2026-11-09 (share-without-verification):</b> a master has shared once an estimate of
 * his reached SENT or SIGNED — the link really left the app. It used to be «owns a link row», but the
 * PWA mints one when the share sheet OPENS, so a master who only looked counted too.</p>
 *
 * <p>The Testcontainers database is shared across the whole run, so nothing here asserts an absolute
 * funnel figure. Each test snapshots the step, adds a known fixture, and asserts the DELTA.</p>
 */
class ActivationFunnelSharedStepIntegrationTest extends IntegrationTestBase {

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateShareLinkRepository estimateShareLinkRepository;
    @Autowired ProjectShareLinkRepository projectShareLinkRepository;
    @Autowired MetricsService metricsService;

    @Test
    void sharedCountsEachMasterOnce_bySentOrSignedEstimate() {
        long before = metricsService.activationFunnel().shared();

        User sent = master(Role.USER, false);
        estimate(objectFor(sent), EstimateStatus.SENT);
        User signedOnly = master(Role.USER, false);
        estimate(objectFor(signedOnly), EstimateStatus.SIGNED);
        User both = master(Role.USER, true);
        Project bothObject = objectFor(both);
        estimate(bothObject, EstimateStatus.SENT);
        estimate(bothObject, EstimateStatus.SIGNED);
        estimate(objectFor(both), EstimateStatus.SENT);

        // 3: one master with two sent sheets and a signed one on two objects is still ONE master.
        assertThat(metricsService.activationFunnel().shared() - before).isEqualTo(3L);
    }

    @Test
    void aLinkMintedOnADraft_isNotSharing() {
        long before = metricsService.activationFunnel().shared();

        // The sheet opened (both kinds of link minted, the portal published) and the master closed it.
        User looked = master(Role.USER, false);
        Project project = objectFor(looked);
        Estimate draft = estimate(project, EstimateStatus.DRAFT);
        estimateShareLinkRepository.save(EstimateShareLink.builder()
                .estimate(draft).token(java.util.UUID.randomUUID().toString()).build());
        projectShareLinkRepository.save(ProjectShareLink.builder()
                .project(project).token(java.util.UUID.randomUUID().toString()).kind(ShareLinkKind.PORTAL).build());

        assertThat(metricsService.activationFunnel().shared()).isEqualTo(before);
    }

    @Test
    void sharedUnverified_countsOnlySharersWhoseEmailIsStillUnverified() {
        long before = metricsService.activationFunnel().sharedUnverified();

        estimate(objectFor(master(Role.USER, false)), EstimateStatus.SENT);  // counts
        estimate(objectFor(master(Role.USER, true)), EstimateStatus.SENT);   // verified
        estimate(objectFor(master(Role.USER, false)), EstimateStatus.DRAFT); // shared nothing

        assertThat(metricsService.activationFunnel().sharedUnverified() - before).isEqualTo(1L);
    }

    @Test
    void anAdminIsInNoFunnelStep_evenWithAnObjectAndASentEstimate() {
        ActivationFunnelResponse before = metricsService.activationFunnel();

        User admin = master(Role.ADMIN, false);
        estimate(objectFor(admin), EstimateStatus.SENT);

        ActivationFunnelResponse after = metricsService.activationFunnel();

        // One demo object on the admin account and the by-source rows (which always filtered the
        // role) would stop summing to the funnel, for a purely technical reason.
        assertThat(after.shared()).isEqualTo(before.shared());
        assertThat(after.sharedUnverified()).isEqualTo(before.sharedUnverified());
        assertThat(after.withProject()).isEqualTo(before.withProject());
        assertThat(after.withEstimate()).isEqualTo(before.withEstimate());
    }

    // ---- fixture ----------------------------------------------------------

    private Estimate estimate(Project project, EstimateStatus status) {
        return estimateRepository.save(Estimate.builder().project(project).status(status).build());
    }

    private Project objectFor(User owner) {
        return projectRepository.save(Project.builder()
                .owner(owner)
                .name("Обʼєкт")
                .address("вул. Тестова, 1")
                .status(ProjectStatus.DRAFT)
                .build());
    }

    private User master(Role role, boolean verified) {
        String unique = java.util.UUID.randomUUID().toString();
        return userRepository.save(User.builder()
                .email(unique + "@majstr.test")
                .emailCanonical(unique + "@majstr.test")
                .passwordHash("x")
                .fullName("Майстер")
                .phone("+380000000000")
                .companyName("ФОП")
                .plan(Plan.FREE)
                .role(role)
                .emailVerified(verified)
                .referralCode(unique.substring(0, 10)) // NOT NULL since V41, no entity default
                .build());
    }
}
