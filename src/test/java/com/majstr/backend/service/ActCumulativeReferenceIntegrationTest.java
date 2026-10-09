package com.majstr.backend.service;

import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActItem;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.entity.WorkActLineKind;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.integration.IntegrationTestBase;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.WorkActItemRepository;
import com.majstr.backend.repository.WorkActRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The «ДОВІДКОВО» block answers two different questions depending on the act's status (review
 * B-77), and both used to be answered wrongly.
 *
 * <p>Lives in the {@code service} package because {@link ActCumulativeCalculator} is
 * package-private — the arithmetic is the whole point here, and going through the rendered PDF to
 * reach it would test the table layout instead.</p>
 */
class ActCumulativeReferenceIntegrationTest extends IntegrationTestBase {

    @Autowired ActCumulativeCalculator calculator;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateItemRepository estimateItemRepository;
    @Autowired WorkActRepository actRepository;
    @Autowired WorkActItemRepository actItemRepository;

    private User owner;
    private Project project;
    private Estimate estimate;
    private EstimateItem line;

    @BeforeEach
    void seed() {
        String u = UUID.randomUUID().toString();
        owner = userRepository.save(User.builder()
                .email(u + "@majstr.test").emailCanonical(u + "@majstr.test").passwordHash("x")
                .fullName("Майстер").phone("+380000000000").companyName("ФОП")
                .plan(Plan.PRO).referralCode(u.substring(0, 10)).build());
        project = projectRepository.save(Project.builder()
                .owner(owner).name("Обʼєкт").address("вул. Тестова, 1")
                .status(ProjectStatus.IN_PROGRESS).build());
        estimate = estimateRepository.save(Estimate.builder()
                .project(project).status(EstimateStatus.SIGNED).countInEconomy(true)
                .signedAt(Instant.now().minus(30, ChronoUnit.DAYS)).build());
        line = estimateItemRepository.save(EstimateItem.builder()
                .estimate(estimate).type(ItemType.WORK).name("Робота").unit(Unit.M2)
                .quantity(new BigDecimal("100.000")).unitPrice(new BigDecimal("100.00"))
                .lineTotal(new BigDecimal("10000.00")).sortOrder(0).build());
    }

    /**
     * An OPEN act's own off-estimate work is in «виконано з початку» but used to be missing from
     * «за кошторисами», because the ADDENDUM that carries it does not exist until the signature.
     * The client read «Залишок −5 000 ₴» on the page he was about to sign.
     */
    @Test
    void anOpenActsOwnExtrasAreOnBothSidesOfTheBlock() {
        signedAct("1", "4000.00", Instant.now().minus(10, ChronoUnit.DAYS));
        WorkAct open = draftAct("2");
        List<WorkActItem> items = List.of(
                estimateLineOn(open, "3000.00"),
                additionalLineOn(open, "5000.00"));

        var ref = calculator.forDownload(open, items, BigDecimal.ZERO);

        assertThat(ref).isNotNull();
        // accepted = act 1 (4 000) + this act's own 3 000 + 5 000
        assertThat(ref.accepted()).isEqualByComparingTo("12000.00");
        // contracted = the estimate's 10 000 + the ADDENDUM signing will create (5 000)
        assertThat(ref.contracted()).isEqualByComparingTo("15000.00");
        assertThat(ref.accepted()).isLessThanOrEqualTo(ref.contracted());
    }

    /**
     * A SIGNED act's block is history: re-rendering it after a LATER act was signed used to print
     * the later act's work inside «виконано з початку», so the document the client already holds
     * said something different every time it was downloaded.
     */
    @Test
    void aSignedActsBlockIsFrozenAtItsOwnSignature() {
        signedAct("1", "1000.00", Instant.now().minus(20, ChronoUnit.DAYS));
        WorkAct second = signedAct("2", "3000.00", Instant.now().minus(10, ChronoUnit.DAYS));
        signedAct("3", "6000.00", Instant.now().minus(1, ChronoUnit.DAYS));

        var ref = calculator.forDownload(second,
                actItemRepository.findByWorkActIdOrderBySortOrderAscIdAsc(second.getId()),
                BigDecimal.ZERO);

        assertThat(ref).isNotNull();
        assertThat(ref.accepted())
                .as("act 3 was signed nine days later and is none of act 2's business")
                .isEqualByComparingTo("4000.00");
        assertThat(ref.contracted()).isEqualByComparingTo("10000.00");
    }

    /**
     * B-94. «First act» was read off TODAY's acts: act 1 had no block when it was signed, and gained
     * one the moment act 2 was — the document the client already held changed shape.
     */
    @Test
    void theFirstActStaysWithoutTheBlockAfterLaterActsAreSigned() {
        WorkAct first = signedAct("1", "4000.00", Instant.now().minus(10, ChronoUnit.DAYS));
        signedAct("2", "6000.00", Instant.now().minus(1, ChronoUnit.DAYS));

        assertThat(calculator.forDownload(first,
                actItemRepository.findByWorkActIdOrderBySortOrderAscIdAsc(first.getId()),
                BigDecimal.ZERO)).isNull();
    }

    /**
     * B-94. A parent superseded by a copy signed AFTER the act still counted when the act was
     * signed; today's {@code count_in_economy = false} took it out of a document already issued.
     */
    @Test
    void aParentSupersededLaterStillCountsAsOfTheActsSignature() {
        signedAct("1", "1000.00", Instant.now().minus(20, ChronoUnit.DAYS));
        WorkAct second = signedAct("2", "3000.00", Instant.now().minus(10, ChronoUnit.DAYS));
        Estimate copy = estimateRepository.save(Estimate.builder()
                .project(project).status(EstimateStatus.SIGNED).countInEconomy(true)
                .signedAt(Instant.now().minus(1, ChronoUnit.DAYS)).build());
        estimate.setCountInEconomy(false);
        estimate.setSupersededByEstimateId(copy.getId());
        estimateRepository.saveAndFlush(estimate);

        var ref = calculator.forDownload(second,
                actItemRepository.findByWorkActIdOrderBySortOrderAscIdAsc(second.getId()),
                BigDecimal.ZERO);

        assertThat(ref.contracted()).isEqualByComparingTo("10000.00");
    }

    /** An estimate signed AFTER the act is not part of the contract the act closed. */
    @Test
    void aLaterEstimateDoesNotEnterAnAlreadySignedActsContractFigure() {
        signedAct("0", "500.00", Instant.now().minus(15, ChronoUnit.DAYS));
        WorkAct first = signedAct("1", "4000.00", Instant.now().minus(10, ChronoUnit.DAYS));
        signedAct("2", "1000.00", Instant.now().minus(9, ChronoUnit.DAYS));
        Estimate later = estimateRepository.save(Estimate.builder()
                .project(project).status(EstimateStatus.SIGNED).countInEconomy(true)
                .signedAt(Instant.now()).build());
        estimateItemRepository.save(EstimateItem.builder()
                .estimate(later).type(ItemType.WORK).name("Ще роботи").unit(Unit.M2)
                .quantity(new BigDecimal("1.000")).unitPrice(new BigDecimal("7000.00"))
                .lineTotal(new BigDecimal("7000.00")).sortOrder(0).build());

        var ref = calculator.forDownload(first,
                actItemRepository.findByWorkActIdOrderBySortOrderAscIdAsc(first.getId()),
                BigDecimal.ZERO);

        assertThat(ref.contracted()).isEqualByComparingTo("10000.00");
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private WorkAct draftAct(String number) {
        return actRepository.save(WorkAct.builder()
                .userId(owner.getId()).project(project).number(number).kind(WorkActKind.INTERIM)
                .status(WorkActStatus.DRAFT).issuedAt(LocalDate.now())
                .periodFrom(LocalDate.now().minusDays(1)).periodTo(LocalDate.now())
                .showCumulative(true).build());
    }

    private WorkAct signedAct(String number, String amount, Instant signedAt) {
        WorkAct act = actRepository.save(WorkAct.builder()
                .userId(owner.getId()).project(project).number(number).kind(WorkActKind.INTERIM)
                .status(WorkActStatus.SIGNED).issuedAt(LocalDate.now())
                .periodFrom(LocalDate.now().minusDays(1)).periodTo(LocalDate.now())
                .signedAt(signedAt).showCumulative(true).build());
        actItemRepository.save(estimateLineOn(act, amount));
        return act;
    }

    private WorkActItem estimateLineOn(WorkAct act, String amount) {
        return WorkActItem.builder()
                .workAct(act).lineKind(WorkActLineKind.ESTIMATE)
                .estimateItemId(line.getId()).estimateId(estimate.getId())
                .type(ItemType.WORK).name("Робота").unit(Unit.M2)
                .unitPrice(new BigDecimal(amount)).quantity(BigDecimal.ONE.setScale(3))
                .lineTotal(new BigDecimal(amount))
                .cumulativeBefore(BigDecimal.ZERO.setScale(3)).sortOrder(0).build();
    }

    private WorkActItem additionalLineOn(WorkAct act, String amount) {
        return WorkActItem.builder()
                .workAct(act).lineKind(WorkActLineKind.ADDITIONAL)
                .estimateItemId(null).estimateId(null)
                .type(ItemType.WORK).name("Поза кошторисом").unit(Unit.PIECE)
                .unitPrice(new BigDecimal(amount)).quantity(BigDecimal.ONE.setScale(3))
                .lineTotal(new BigDecimal(amount))
                .cumulativeBefore(BigDecimal.ZERO.setScale(3)).sortOrder(1).build();
    }
}
