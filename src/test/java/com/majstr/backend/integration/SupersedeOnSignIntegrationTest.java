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

        publicService.sign(link.getToken(), new SignRequest("Марія Петренко", "+380672222222"), "203.0.113.42");

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

    // ---- fixtures ---------------------------------------------------------------

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
