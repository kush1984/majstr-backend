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
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.ProjectShareLinkRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.WorkActRepository;
import com.majstr.backend.service.MetricsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The funnel's {@code shared} step, as real SQL against real Postgres.
 *
 * <p><b>Why an integration test.</b> The step is a union over TWO tables reached by two different
 * paths ({@code EstimateShareLink → estimate → project → owner} and {@code ProjectShareLink →
 * project → owner}), with a {@code kind} filter and a deliberately ABSENT {@code revoked} filter. A
 * Mockito test proves none of that — it returns whatever the stub is told, which is exactly how the
 * original bug survived: {@code shared} read one table only, every master who shared from the object
 * counted as never having shared, and the admin funnel drew a cliff at «кошторис → поділився» that
 * did not exist.</p>
 *
 * <p>The Testcontainers database is shared across the whole run, so nothing here asserts an absolute
 * funnel figure. Each test snapshots the step, adds a known fixture, and asserts the DELTA — which
 * is also what makes the double-count assertion meaningful.</p>
 */
class ActivationFunnelSharedStepIntegrationTest extends IntegrationTestBase {

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateShareLinkRepository estimateShareLinkRepository;
    @Autowired ProjectShareLinkRepository projectShareLinkRepository;
    @Autowired WorkActRepository workActRepository;
    @Autowired MetricsService metricsService;

    @Test
    void sharedCountsEachMasterOnce_acrossBothLinkTables() {
        long before = metricsService.activationFunnel().shared();

        UUID objectOnly = objectSharer(ShareLinkKind.PORTAL);
        UUID estimateOnly = estimateSharer();
        UUID both = objectSharer(ShareLinkKind.ECONOMY);
        estimateLinkFor(both);

        long after = metricsService.activationFunnel().shared();

        // 3, not 1 (the old estimate-only behaviour) and not 4 (summing two counts would count the
        // master holding both kinds of link twice).
        assertThat(after - before).isEqualTo(3L);
        assertThat(sharedOwnerIds()).contains(objectOnly, estimateOnly, both);
    }

    @Test
    void anActLinkIsSharing_butAMessageLinkIsNot() {
        UUID actSharer = actSharer();
        UUID messageOnly = objectSharer(ShareLinkKind.MESSAGE);

        Set<UUID> shared = sharedOwnerIds();

        assertThat(shared).contains(actSharer);
        // A MESSAGE link opens a contact form — masters mint it for suppliers and colleagues.
        // Counting it would award «поділився з клієнтом» to somebody who sent the client nothing.
        assertThat(shared).doesNotContain(messageOnly);
    }

    @Test
    void aRevokedLinkStillCounts_becauseTheStepMeansEverShared() {
        User master = master(Role.USER);
        UUID owner = master.getId();
        ProjectShareLink link = projectShareLinkRepository.save(ProjectShareLink.builder()
                .project(objectFor(master))
                .token(UUID.randomUUID().toString())
                .kind(ShareLinkKind.PORTAL)
                .build());
        link.setRevoked(true);
        projectShareLinkRepository.save(link);

        // Two of the three pre-existing lookups on this repository carry `AndRevokedFalse`, so
        // copying the filter into the funnel query is the tempting mistake. It would make a funnel
        // step SHRINK as masters revoke old links, which an "ever reached this state" step cannot do.
        assertThat(sharedOwnerIds()).contains(owner);
    }

    @Test
    void anAdminIsInNoFunnelStep_evenWithAnObjectAnEstimateAndALink() {
        ActivationFunnelResponse before = metricsService.activationFunnel();

        User admin = master(Role.ADMIN);
        Project project = objectFor(admin);
        estimateShareLinkRepository.save(EstimateShareLink.builder()
                .estimate(estimateFor(project))
                .token(UUID.randomUUID().toString())
                .build());
        projectShareLinkRepository.save(ProjectShareLink.builder()
                .project(project)
                .token(UUID.randomUUID().toString())
                .kind(ShareLinkKind.PORTAL)
                .build());

        ActivationFunnelResponse after = metricsService.activationFunnel();

        // The javadoc used to say the distinct-owner steps are "naturally master-only (admins have
        // no projects)". One demo object on the admin account and the by-source rows (which always
        // filtered the role) stop summing to the funnel, for a purely technical reason.
        assertThat(after.shared()).isEqualTo(before.shared());
        assertThat(after.withProject()).isEqualTo(before.withProject());
        assertThat(after.withEstimate()).isEqualTo(before.withEstimate());
    }

    // ---- fixture ----------------------------------------------------------

    private Set<UUID> sharedOwnerIds() {
        Set<UUID> ids = new HashSet<>();
        estimateShareLinkRepository.findSharedOwners().forEach(row -> ids.add(row.getOwnerId()));
        projectShareLinkRepository.findSharedOwners(ShareLinkKind.SHARED_WITH_CLIENT)
                .forEach(row -> ids.add(row.getOwnerId()));
        return ids;
    }

    private UUID objectSharer(ShareLinkKind kind) {
        User owner = master(Role.USER);
        projectShareLinkRepository.save(ProjectShareLink.builder()
                .project(objectFor(owner))
                .token(UUID.randomUUID().toString())
                .kind(kind)
                .build());
        return owner.getId();
    }

    private UUID actSharer() {
        User owner = master(Role.USER);
        Project project = objectFor(owner);
        WorkAct act = workActRepository.save(WorkAct.builder()
                .userId(owner.getId())
                .project(project)
                .number("1")
                .kind(WorkActKind.INTERIM)
                .status(WorkActStatus.SENT)
                .issuedAt(LocalDate.now())
                .periodFrom(LocalDate.now().minusDays(7))
                .periodTo(LocalDate.now())
                .build());
        projectShareLinkRepository.save(ProjectShareLink.builder()
                .project(project)
                .workAct(act)
                .token(UUID.randomUUID().toString())
                .kind(ShareLinkKind.ACT)
                .build());
        return owner.getId();
    }

    private UUID estimateSharer() {
        User owner = master(Role.USER);
        estimateShareLinkRepository.save(EstimateShareLink.builder()
                .estimate(estimateFor(objectFor(owner)))
                .token(UUID.randomUUID().toString())
                .build());
        return owner.getId();
    }

    private void estimateLinkFor(UUID ownerId) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        estimateShareLinkRepository.save(EstimateShareLink.builder()
                .estimate(estimateFor(objectFor(owner)))
                .token(UUID.randomUUID().toString())
                .build());
    }

    private Estimate estimateFor(Project project) {
        return estimateRepository.save(Estimate.builder()
                .project(project)
                .status(EstimateStatus.SENT)
                .build());
    }

    private Project objectFor(User owner) {
        return projectRepository.save(Project.builder()
                .owner(owner)
                .name("Обʼєкт")
                .address("вул. Тестова, 1")
                .status(ProjectStatus.DRAFT)
                .build());
    }

    private User master(Role role) {
        String unique = UUID.randomUUID().toString();
        return userRepository.save(User.builder()
                .email(unique + "@majstr.test")
                .emailCanonical(unique + "@majstr.test")
                .passwordHash("x")
                .fullName("Майстер")
                .phone("+380000000000")
                .companyName("ФОП")
                .plan(Plan.FREE)
                .role(role)
                .referralCode(unique.substring(0, 10)) // NOT NULL since V41, no entity default
                .build());
    }
}
