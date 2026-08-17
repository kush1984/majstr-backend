package com.majstr.backend.integration;

import com.majstr.backend.dto.ActProgressResponse;
import com.majstr.backend.dto.WorkActCreateRequest;
import com.majstr.backend.dto.WorkActItemsRequest;
import com.majstr.backend.dto.WorkActResponse;
import com.majstr.backend.dto.WorkActSignOfflineRequest;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateKind;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.dto.ObjectEconomyResponse;
import com.majstr.backend.exception.WorkActConflictException;
import com.majstr.backend.exception.WorkActOpenException;
import com.majstr.backend.exception.WorkActSignedException;
import com.majstr.backend.exception.WorkActValidationException;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.WorkActRepository;
import com.majstr.backend.service.WorkActService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Work acts end-to-end through the real {@link WorkActService} against real Postgres — the class of
 * test the acts core needs (numbering, the SIGNED-quantity aggregate, the UNIQUE constraint, the
 * ADDENDUM transaction all live in SQL / cross-entity logic no Mockito test can reach).
 */
class WorkActIntegrationTest extends IntegrationTestBase {

    @Autowired WorkActService workActService;
    @Autowired com.majstr.backend.service.ObjectExpenseService objectExpenseService;
    @Autowired com.majstr.backend.service.EstimateService estimateService;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateItemRepository estimateItemRepository;
    @Autowired WorkActRepository workActRepository;

    @Test
    void numberingIsContinuousPerMaster_acrossObjects() {
        User owner = newOwner();
        Project a = newProject(owner);
        Project b = newProject(owner);
        signedEstimateWithLine(a, "Робота A", "100.000", "145.00");
        signedEstimateWithLine(b, "Робота B", "100.000", "145.00");

        // A draft act on object A does NOT block a first act on object B (one-open-act is per
        // object), and the running numbers are continuous for the master: 1, then 2.
        WorkActResponse act1 = createInterim(a.getId(), owner.getId());
        WorkActResponse act2 = createInterim(b.getId(), owner.getId());

        assertThat(act1.number()).isEqualTo("1");
        assertThat(act2.number()).isEqualTo("2");
    }

    @Test
    void oneOpenActPerObject() {
        User owner = newOwner();
        Project p = newProject(owner);
        signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        createInterim(p.getId(), owner.getId());

        assertThatThrownBy(() -> createInterim(p.getId(), owner.getId()))
                .isInstanceOf(WorkActOpenException.class);
    }

    @Test
    void uniqueUserNumberConstraintIsEnforced() {
        User owner = newOwner();
        Project p = newProject(owner);
        workActRepository.saveAndFlush(rawAct(owner, p, "7", WorkActKind.INTERIM));

        assertThatThrownBy(() -> workActRepository.saveAndFlush(rawAct(owner, p, "7", WorkActKind.INTERIM)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cumulativeProgressAccumulatesAcrossSignedActs_frozenPerAct() {
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Шпаклювання", "100.000", "145.00");
        UUID lineId = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();

        // Act 1: close 40 of 100, sign it.
        WorkActResponse act1 = createInterim(p.getId(), owner.getId());
        setSingleLine(act1.id(), owner.getId(), est.getId(), lineId, "40.000", "145.00");
        workActService.signOffline(act1.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        // Progress now shows 40 done, 60 remaining.
        ActProgressResponse progress = workActService.progress(p.getId(), owner.getId());
        ActProgressResponse.Line pl = progress.lines().stream()
                .filter(l -> l.estimateItemId().equals(lineId)).findFirst().orElseThrow();
        assertThat(pl.done()).isEqualByComparingTo("40.000");
        assertThat(pl.remaining()).isEqualByComparingTo("60.000");

        // Act 2: its line freezes cumulative_before = 40, closes 30 more.
        WorkActResponse act2 = createInterim(p.getId(), owner.getId());
        WorkActResponse act2WithItems = setSingleLine(act2.id(), owner.getId(), est.getId(), lineId, "30.000", "145.00");
        assertThat(act2WithItems.items().get(0).cumulativeBefore()).isEqualByComparingTo("40.000");
        workActService.signOffline(act2.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        ActProgressResponse after = workActService.progress(p.getId(), owner.getId());
        ActProgressResponse.Line pl2 = after.lines().stream()
                .filter(l -> l.estimateItemId().equals(lineId)).findFirst().orElseThrow();
        assertThat(pl2.done()).isEqualByComparingTo("70.000");
        assertThat(pl2.remaining()).isEqualByComparingTo("30.000");
    }

    @Test
    void exceedsEstimateFlaggedWhenOverTheEstimateQuantity() {
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        UUID lineId = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();

        WorkActResponse act = createInterim(p.getId(), owner.getId());
        WorkActResponse withItems = setSingleLine(act.id(), owner.getId(), est.getId(), lineId, "120.000", "145.00");

        assertThat(withItems.items().get(0).exceedsEstimate()).isTrue();
    }

    @Test
    void signedActIsImmutable_andOnlyDraftOrRejectedDeletable() {
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        UUID lineId = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();

        WorkActResponse act = createInterim(p.getId(), owner.getId());
        setSingleLine(act.id(), owner.getId(), est.getId(), lineId, "50.000", "145.00");
        workActService.signOffline(act.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        assertThatThrownBy(() -> setSingleLine(act.id(), owner.getId(), est.getId(), lineId, "10.000", "145.00"))
                .isInstanceOf(WorkActSignedException.class);
        assertThatThrownBy(() -> workActService.delete(act.id(), owner.getId()))
                .isInstanceOf(WorkActConflictException.class);
    }

    @Test
    void signingWithAdditionalWorks_createsSignedAddendumEstimate() {
        User owner = newOwner();
        Project p = newProject(owner);
        signedEstimateWithLine(p, "Робота", "100.000", "145.00");

        WorkActResponse act = createInterim(p.getId(), owner.getId());
        // A line with NO estimateItemId = additional work not in any estimate.
        workActService.replaceItems(act.id(), new WorkActItemsRequest(List.of(
                new WorkActItemsRequest.Line(null, null, ItemType.WORK, "Демонтаж стіни", null,
                        Unit.M2, new BigDecimal("500.00"), new BigDecimal("3.000")))), owner.getId());
        WorkActResponse signed = workActService.signOffline(act.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        assertThat(signed.addendumEstimateId()).isNotNull();
        Estimate addendum = estimateRepository.findById(signed.addendumEstimateId()).orElseThrow();
        assertThat(addendum.getKind()).isEqualTo(EstimateKind.ADDENDUM);
        assertThat(addendum.getStatus()).isEqualTo(EstimateStatus.SIGNED);
        assertThat(addendum.isCountInEconomy()).isTrue();
        assertThat(addendum.isPortalVisible()).isFalse();
        assertThat(estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(addendum.getId()))
                .singleElement()
                .satisfies(i -> assertThat(i.getLineTotal()).isEqualByComparingTo("1500.00")); // 500 × 3
    }

    @Test
    void finalActClosesTheObject() {
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        UUID lineId = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();

        WorkActResponse fin = workActService.create(p.getId(),
                new WorkActCreateRequest(WorkActKind.FINAL, LocalDate.now(), LocalDate.now().minusDays(7),
                        LocalDate.now(), null, null, null, null, null, null), owner.getId(), null);
        setSingleLine(fin.id(), owner.getId(), est.getId(), lineId, "100.000", "145.00");
        workActService.signOffline(fin.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        assertThatThrownBy(() -> createInterim(p.getId(), owner.getId()))
                .isInstanceOf(WorkActConflictException.class);
    }

    @Test
    void reopeningTheParentEstimate_doesNotBreakASignedAct() {
        // The act's lines are frozen copies, and estimate_item_id / estimate_id are ON DELETE SET
        // NULL — so an owner reopening the estimate (SIGNED → DRAFT, signature cleared) leaves an
        // act already signed against it completely intact. This is the invariant Prompt 0 protects.
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        UUID lineId = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();

        WorkActResponse act = createInterim(p.getId(), owner.getId());
        setSingleLine(act.id(), owner.getId(), est.getId(), lineId, "60.000", "145.00");
        workActService.signOffline(act.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        // Owner reopens the estimate (allowed: it's SIGNED).
        estimateService.reopen(est.getId(), owner.getId());

        WorkActResponse reloaded = workActService.get(act.id(), owner.getId());
        assertThat(reloaded.status()).isEqualTo(WorkActStatus.SIGNED);
        assertThat(reloaded.items()).singleElement()
                .satisfies(i -> assertThat(i.quantity()).isEqualByComparingTo("60.000"));
    }

    @Test
    void excludedEstimate_isNotOfferedInThePicker_andCannotBeClosedByAnAct() {
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate counted = signedEstimateWithLine(p, "Лічена", "100.000", "145.00", true);
        Estimate excluded = signedEstimateWithLine(p, "Виключена", "50.000", "200.00", false);
        UUID excludedLine = estimateItemRepository
                .findByEstimateIdOrderBySortOrderAscIdAsc(excluded.getId()).get(0).getId();

        // The picker offers only the counted estimate's lines.
        ActProgressResponse progress = workActService.progress(p.getId(), owner.getId());
        assertThat(progress.lines()).isNotEmpty()
                .allSatisfy(l -> assertThat(l.estimateId()).isEqualTo(counted.getId()));

        // Defense-in-depth: the write path rejects a line from the excluded estimate.
        WorkActResponse act = createInterim(p.getId(), owner.getId());
        assertThatThrownBy(() ->
                setSingleLine(act.id(), owner.getId(), excluded.getId(), excludedLine, "10.000", "200.00"))
                .isInstanceOf(WorkActValidationException.class);
    }

    @Test
    void acceptedByActs_countsCountedAndAdditionalOnly_neverExceedingContracted() {
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate counted = signedEstimateWithLine(p, "Лічена", "100.000", "145.00", true); // 14 500
        signedEstimateWithLine(p, "Виключена", "50.000", "200.00", false); // 10 000, excluded from economy
        UUID countedLine = estimateItemRepository
                .findByEstimateIdOrderBySortOrderAscIdAsc(counted.getId()).get(0).getId();

        // Act closes 50 of the counted line (7 250) + an additional work (500 × 2 = 1 000).
        WorkActResponse act = createInterim(p.getId(), owner.getId());
        workActService.replaceItems(act.id(), new WorkActItemsRequest(List.of(
                new WorkActItemsRequest.Line(countedLine, counted.getId(), ItemType.WORK, "Робота", null,
                        Unit.M2, new BigDecimal("145.00"), new BigDecimal("50.000")),
                new WorkActItemsRequest.Line(null, null, ItemType.WORK, "Демонтаж", null,
                        Unit.M2, new BigDecimal("500.00"), new BigDecimal("2.000")))), owner.getId());
        workActService.signOffline(act.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        ObjectEconomyResponse economy = objectExpenseService.economy(p.getId(), owner.getId());
        // contracted = counted estimate (14 500) + the additional-works ADDENDUM (1 000) = 15 500;
        // the excluded estimate (10 000) is in NEITHER axis.
        assertThat(economy.acts().contracted()).isEqualByComparingTo("15500.00");
        // acceptedByActs = counted line 7 250 + additional 1 000 = 8 250 — the excluded estimate's
        // work never enters the numerator, so the numerator stays within the denominator.
        assertThat(economy.acts().acceptedByActs()).isEqualByComparingTo("8250.00");
        assertThat(economy.acts().acceptedByActs()).isLessThanOrEqualTo(economy.acts().contracted());
    }

    // ---- fixtures ---------------------------------------------------------------

    private WorkActResponse createInterim(UUID projectId, UUID ownerId) {
        return workActService.create(projectId,
                new WorkActCreateRequest(WorkActKind.INTERIM, LocalDate.now(), LocalDate.now().minusDays(7),
                        LocalDate.now(), null, null, null, null, null, null), ownerId, null);
    }

    private WorkActResponse setSingleLine(UUID actId, UUID ownerId, UUID estimateId, UUID estimateItemId,
                                          String qty, String price) {
        return workActService.replaceItems(actId, new WorkActItemsRequest(List.of(
                new WorkActItemsRequest.Line(estimateItemId, estimateId, ItemType.WORK, "Робота", null,
                        Unit.M2, new BigDecimal(price), new BigDecimal(qty)))), ownerId);
    }

    private User newOwner() {
        String u = UUID.randomUUID().toString();
        return userRepository.save(User.builder()
                .email(u + "@majstr.test").emailCanonical(u + "@majstr.test").passwordHash("x")
                .fullName("Майстер").phone("+380000000000").companyName("ФОП")
                .plan(Plan.PRO).referralCode(u.substring(0, 10)).build());
    }

    private Project newProject(User owner) {
        return projectRepository.save(Project.builder()
                .owner(owner).name("Обʼєкт").address("вул. Тестова, 1")
                .status(ProjectStatus.IN_PROGRESS).build());
    }

    private Estimate signedEstimateWithLine(Project project, String name, String qty, String price) {
        return signedEstimateWithLine(project, name, qty, price, true);
    }

    private Estimate signedEstimateWithLine(Project project, String name, String qty, String price, boolean counted) {
        Estimate est = estimateRepository.save(Estimate.builder()
                .project(project).status(EstimateStatus.SIGNED).countInEconomy(counted).build());
        estimateItemRepository.save(EstimateItem.builder()
                .estimate(est).type(ItemType.WORK).name(name).unit(Unit.M2)
                .quantity(new BigDecimal(qty)).unitPrice(new BigDecimal(price))
                .lineTotal(new BigDecimal(qty).multiply(new BigDecimal(price)).setScale(2))
                .sortOrder(0).build());
        return est;
    }

    private WorkAct rawAct(User owner, Project project, String number, WorkActKind kind) {
        return WorkAct.builder()
                .userId(owner.getId()).project(project).number(number).kind(kind)
                .status(WorkActStatus.DRAFT).issuedAt(LocalDate.now())
                .periodFrom(LocalDate.now().minusDays(1)).periodTo(LocalDate.now()).build();
    }
}
