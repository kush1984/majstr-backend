package com.majstr.backend.integration;

import com.majstr.backend.dto.EstimateItemRequest;
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
import com.majstr.backend.exception.DocumentChangedException;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.PublicEstimateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-86 — the client signs exactly the sheet he read.
 *
 * <p>The portal sends back the estimate's {@code @Version} with the signature, and a mismatch is a
 * 409. But the version only moved when the estimate ROW was written, and a line edit writes the
 * item rows alone: a client holding the page at version 0 signed a sheet the master had since added
 * 5 000 ₴ to. The Mockito test could not catch it — it hands in a mismatched number by hand.</p>
 */
class EstimateVersionOnLineEditIntegrationTest extends IntegrationTestBase {

    @Autowired EstimateService estimateService;
    @Autowired PublicEstimateService publicService;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateItemRepository itemRepository;
    @Autowired EstimateShareLinkRepository shareLinkRepository;

    @Test
    void aLineAddedAfterTheClientOpenedTheSheet_makesHisSignatureStale() {
        Fixture f = sentEstimate();
        long seen = version(f.estimate);

        estimateService.addItem(f.estimate.getId(), new EstimateItemRequest(
                ItemType.WORK, "Додаткова робота", null, Unit.M2, new BigDecimal("1.000"),
                new BigDecimal("5000.00"), null, null, false, null, null), f.ownerId);

        assertThat(version(f.estimate)).isGreaterThan(seen);
        assertThatThrownBy(() -> sign(f, seen)).isInstanceOf(DocumentChangedException.class);
        assertThat(estimateRepository.findById(f.estimate.getId()).orElseThrow().getStatus())
                .isNotEqualTo(EstimateStatus.SIGNED);
    }

    @Test
    void deletingALine_movesTheVersionToo() {
        Fixture f = sentEstimate();
        long seen = version(f.estimate);
        UUID line = itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(f.estimate.getId()).get(0).getId();

        estimateService.deleteItems(f.estimate.getId(), List.of(line), f.ownerId);

        assertThat(version(f.estimate)).isGreaterThan(seen);
    }

    @Test
    void theSheetHeReadIsStillSignable() {
        Fixture f = sentEstimate();

        sign(f, version(f.estimate));

        assertThat(estimateRepository.findById(f.estimate.getId()).orElseThrow().getStatus())
                .isEqualTo(EstimateStatus.SIGNED);
    }

    // ---- fixtures ---------------------------------------------------------------

    private record Fixture(UUID ownerId, Estimate estimate, String token) {}

    private Fixture sentEstimate() {
        String unique = UUID.randomUUID().toString();
        User owner = userRepository.save(User.builder()
                .email(unique + "@majstr.test").emailCanonical(unique + "@majstr.test").passwordHash("x")
                .fullName("Майстер").phone("+380000000000").companyName("ФОП")
                .plan(Plan.PRO) // ONLINE_SIGNATURE is PRO-gated
                .referralCode(unique.substring(0, 10)).build());
        Project project = projectRepository.save(Project.builder()
                .owner(owner).name("Обʼєкт").address("вул. Тестова, 1")
                .status(ProjectStatus.IN_PROGRESS).build());
        Estimate estimate = estimateRepository.save(Estimate.builder()
                .project(project).status(EstimateStatus.SENT).countInEconomy(true).build());
        itemRepository.save(EstimateItem.builder()
                .estimate(estimate).type(ItemType.WORK).name("Роботи").unit(Unit.M2)
                .quantity(new BigDecimal("100.000")).unitPrice(new BigDecimal("200.00"))
                .lineTotal(new BigDecimal("20000.00")).sortOrder(0).build());
        EstimateShareLink link = shareLinkRepository.save(EstimateShareLink.builder()
                .estimate(estimate).token("tok-" + UUID.randomUUID()).build());
        return new Fixture(owner.getId(), estimate, link.getToken());
    }

    private long version(Estimate estimate) {
        return estimateRepository.findById(estimate.getId()).orElseThrow().getVersion();
    }

    private void sign(Fixture f, long version) {
        publicService.sign(f.token, new SignRequest("Марія Петренко", "+380672222222", version),
                "203.0.113.42");
    }
}
