package com.majstr.backend.integration;

import com.majstr.backend.dto.SignRequest;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateShareLink;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.PublicEstimateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signing a duplicate whose parent is still SIGNED, driven end-to-end through the real
 * {@link PublicEstimateService} against real Postgres.
 *
 * <p>The acts iteration changed the supersede rule: the parent used to be auto-reopened to DRAFT
 * (its signature rewritten), which is both dishonest — the client really signed it — and, once
 * work acts reference a signed estimate, unsafe. Now the parent keeps its signature and simply
 * stops counting in the object's economy. The Mockito test covers the branch in isolation; this
 * one proves the two things a mock cannot: that the {@code count_in_economy} correction actually
 * prevents the double-count in the native economy sum, and that the parent stays a signed panel.</p>
 */
class SupersedeOnSignIntegrationTest extends IntegrationTestBase {

    @Autowired PublicEstimateService publicService;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateItemRepository itemRepository;
    @Autowired EstimateShareLinkRepository shareLinkRepository;

    @Test
    void signingADuplicate_keepsTheParentSigned_stopsItCounting_andDoesNotDoubleTheEconomy() {
        User owner = userRepository.save(newOwner());
        Project project = projectRepository.save(Project.builder()
                .owner(owner)
                .name("Обʼєкт")
                .address("вул. Тестова, 1")
                .status(ProjectStatus.IN_PROGRESS)
                .build());

        // Parent: signed first, counting, with a real signature on record.
        Estimate parent = estimateRepository.save(Estimate.builder()
                .project(project)
                .status(EstimateStatus.SIGNED)
                .countInEconomy(true)
                .signedAt(Instant.now())
                .signerName("Олена Іваненко")
                .signerPhone("+380671111111")
                .build());
        addWork(parent, "10000.00");

        // Duplicate of the parent, still a DRAFT the client is about to sign via a legacy link.
        Estimate duplicate = estimateRepository.save(Estimate.builder()
                .project(project)
                .status(EstimateStatus.DRAFT)
                .countInEconomy(true)
                .duplicatedFromId(parent.getId())
                .build());
        addWork(duplicate, "9000.00");
        EstimateShareLink link = shareLinkRepository.save(EstimateShareLink.builder()
                .estimate(duplicate)
                .token("tok-" + UUID.randomUUID())
                .build());

        publicService.sign(link.getToken(), new SignRequest("Марія Петренко", "+380672222222",
                        estimateRepository.findById(duplicate.getId()).orElseThrow().getVersion()), "203.0.113.42");

        Estimate reloadedParent = estimateRepository.findById(parent.getId()).orElseThrow();
        Estimate reloadedDuplicate = estimateRepository.findById(duplicate.getId()).orElseThrow();

        // The duplicate is now the live signed deal.
        assertThat(reloadedDuplicate.getStatus()).isEqualTo(EstimateStatus.SIGNED);

        // The parent keeps its signature untouched — nothing rewritten.
        assertThat(reloadedParent.getStatus()).isEqualTo(EstimateStatus.SIGNED);
        assertThat(reloadedParent.getSignedAt()).isNotNull();
        assertThat(reloadedParent.getSignerName()).isEqualTo("Олена Іваненко");
        // …but it stops counting, and records which duplicate replaced it.
        assertThat(reloadedParent.isCountInEconomy()).isFalse();
        assertThat(reloadedParent.getSupersededByEstimateId()).isEqualTo(duplicate.getId());

        // The whole point: the object's counted income is the duplicate ALONE (9000), never the
        // parent + duplicate double-count (19000) the old workaround existed to avoid.
        assertThat(estimateRepository.sumIncomeCounted(project.getId())).isEqualByComparingTo("9000.00");

        // Both remain SIGNED panels — the parent didn't fall out of the acts list, it's just flagged
        // uncounted (count_in_economy rides along on the row).
        List<Object[]> panels = estimateRepository.findSignedEstimateSummaries(project.getId());
        assertThat(panels).hasSize(2);
        assertThat(panels).anySatisfy(row -> {
            assertThat((UUID) row[0]).isEqualTo(parent.getId());
            assertThat((Boolean) row[2]).isFalse(); // count_in_economy on the superseded parent
        });
    }

    @Test
    void signingAConsolidatedRollup_makesItTheContract_andUncountsItsSources() {
        // B-68. consolidate() creates the rollup uncounted so it cannot double-count its sources —
        // right until the client signs the ROLLUP. «За договором» counts SIGNED ∧ counted, and then
        // nothing on the object was both: a 50 000 ₴ signed deal read 0 ₴, and no act could be made
        // against it (the progress picker is SIGNED ∧ counted too).
        User owner = userRepository.save(newOwner());
        Project project = newProject(owner);

        Estimate a = draft(project, "30000.00");
        Estimate b = draft(project, "20000.00");
        Estimate rollup = estimateRepository.save(Estimate.builder()
                .project(project).status(EstimateStatus.DRAFT).countInEconomy(false).build());
        addWork(rollup, "50000.00");
        rollup.setConsolidationSourceIds(new LinkedHashSet<>(List.of(a.getId(), b.getId())));
        estimateRepository.saveAndFlush(rollup);

        signViaLink(rollup);

        assertThat(estimateRepository.findById(rollup.getId()).orElseThrow().isCountInEconomy()).isTrue();
        assertThat(estimateRepository.findById(a.getId()).orElseThrow().isCountInEconomy()).isFalse();
        assertThat(estimateRepository.findById(b.getId()).orElseThrow().isCountInEconomy()).isFalse();
        // 50 000 once — the signed rollup — never 50 000 + its two sources.
        assertThat(estimateRepository.sumIncomeCounted(project.getId())).isEqualByComparingTo("50000.00");
    }

    @Test
    void signingARollupOverAnAlreadySignedSource_movesNothing() {
        // Deliberately narrow (B-68): a source that is already SIGNED ∧ counted IS the contract, and
        // counting the rollup beside it would double exactly what the original `false` protected.
        User owner = userRepository.save(newOwner());
        Project project = newProject(owner);

        Estimate signedSource = estimateRepository.save(Estimate.builder()
                .project(project).status(EstimateStatus.SIGNED).countInEconomy(true)
                .signedAt(Instant.now()).signerName("Олена").signerPhone("+380671111111").build());
        addWork(signedSource, "30000.00");
        Estimate rollup = estimateRepository.save(Estimate.builder()
                .project(project).status(EstimateStatus.DRAFT).countInEconomy(false).build());
        addWork(rollup, "30000.00");
        rollup.setConsolidationSourceIds(new LinkedHashSet<>(List.of(signedSource.getId())));
        estimateRepository.saveAndFlush(rollup);

        signViaLink(rollup);

        assertThat(estimateRepository.findById(rollup.getId()).orElseThrow().isCountInEconomy()).isFalse();
        assertThat(estimateRepository.findById(signedSource.getId()).orElseThrow().isCountInEconomy()).isTrue();
        assertThat(estimateRepository.sumIncomeCounted(project.getId())).isEqualByComparingTo("30000.00");
    }

    // ---- fixtures ---------------------------------------------------------------

    private Project newProject(User owner) {
        return projectRepository.save(Project.builder()
                .owner(owner).name("Обʼєкт").address("вул. Тестова, 1")
                .status(ProjectStatus.IN_PROGRESS).build());
    }

    private Estimate draft(Project project, String amount) {
        Estimate e = estimateRepository.save(Estimate.builder()
                .project(project).status(EstimateStatus.DRAFT).countInEconomy(true).build());
        addWork(e, amount);
        return e;
    }

    private void signViaLink(Estimate estimate) {
        EstimateShareLink link = shareLinkRepository.save(EstimateShareLink.builder()
                .estimate(estimate).token("tok-" + UUID.randomUUID()).build());
        publicService.sign(link.getToken(), new SignRequest("Марія Петренко", "+380672222222",
                estimateRepository.findById(estimate.getId()).orElseThrow().getVersion()), "203.0.113.42");
    }



    /**
     * B-73. A master prices three variants off one sheet and sends them all. {@code doSign} only
     * ever looked at the PARENT, so two siblings could both end up SIGNED ∧ counted and «За
     * договором» read the sum of two quotes for one job. The latest signature wins; the earlier
     * variant keeps its signature and stops counting, exactly like a superseded parent.
     */
    @Test
    void signingASecondSiblingCopy_uncountsTheFirstInsteadOfDoublingTheContract() {
        User owner = userRepository.save(newOwner());
        Project project = projectRepository.save(Project.builder()
                .owner(owner).name("Обʼєкт").address("вул. Тестова, 1")
                .status(ProjectStatus.IN_PROGRESS).build());
        Estimate parent = estimateRepository.save(Estimate.builder()
                .project(project).status(EstimateStatus.DRAFT).countInEconomy(true).build());
        addWork(parent, "10000.00");

        Estimate variantA = estimateRepository.save(Estimate.builder()
                .project(project).status(EstimateStatus.DRAFT).countInEconomy(true)
                .duplicatedFromId(parent.getId()).build());
        addWork(variantA, "12000.00");
        Estimate variantB = estimateRepository.save(Estimate.builder()
                .project(project).status(EstimateStatus.DRAFT).countInEconomy(true)
                .duplicatedFromId(parent.getId()).build());
        addWork(variantB, "11000.00");

        signViaLink(variantA);
        signViaLink(variantB);

        Estimate reloadedA = estimateRepository.findById(variantA.getId()).orElseThrow();
        assertThat(reloadedA.getStatus()).as("the signature he really gave is history, not a lie")
                .isEqualTo(EstimateStatus.SIGNED);
        assertThat(reloadedA.isCountInEconomy()).isFalse();
        assertThat(reloadedA.getSupersededByEstimateId()).isEqualTo(variantB.getId());
        assertThat(estimateRepository.sumIncomeCounted(project.getId()))
                .as("one job, one contract")
                .isEqualByComparingTo("11000.00");
    }

    private User newOwner() {
        String unique = UUID.randomUUID().toString();
        return User.builder()
                .email(unique + "@majstr.test")
                .emailCanonical(unique + "@majstr.test")
                .passwordHash("x")
                .fullName("Майстер")
                .phone("+380000000000")
                .companyName("ФОП")
                .plan(Plan.PRO) // ONLINE_SIGNATURE is PRO-gated; doSign requires it
                .referralCode(unique.substring(0, 10))
                .build();
    }

    /** One WORK line, quantity 1, so line_total equals the amount. lineTotal is set explicitly
     *  because these fixtures bypass the service (the only thing that writes it in prod, V88). */
    private void addWork(Estimate estimate, String amount) {
        itemRepository.save(EstimateItem.builder()
                .estimate(estimate)
                .type(ItemType.WORK)
                .name("Роботи")
                .unit(Unit.M2)
                .quantity(new BigDecimal("1.000"))
                .unitPrice(new BigDecimal(amount))
                .lineTotal(new BigDecimal(amount).setScale(2))
                .sortOrder(0)
                .build());
    }
}
