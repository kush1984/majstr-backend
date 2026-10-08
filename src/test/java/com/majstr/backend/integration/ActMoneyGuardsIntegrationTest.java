package com.majstr.backend.integration;

import com.majstr.backend.dto.WorkActCreateRequest;
import com.majstr.backend.dto.WorkActItemsRequest;
import com.majstr.backend.dto.WorkActResponse;
import com.majstr.backend.dto.WorkActSignOfflineRequest;
import com.majstr.backend.dto.WorkActUpdateRequest;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.exception.WorkActValidationException;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.WorkActRepository;
import com.majstr.backend.service.WorkActService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The money guards review round 3 §4 added to an act, and the database indexes that make two of
 * them true for a concurrent pair as well (B-78, B-79, B-80, B-82, V144).
 *
 * <p>These are integration tests because every one of them is about something Mockito cannot see:
 * a partial unique index, an optimistic-lock column, an aggregate over two tables, or a guard that
 * has to hold at three different doors.</p>
 */
class ActMoneyGuardsIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired WorkActService workActService;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateItemRepository estimateItemRepository;
    @Autowired WorkActRepository actRepository;
    @Autowired com.majstr.backend.repository.WorkActReceiptRepository receiptRepository;

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
                .project(project).status(EstimateStatus.SIGNED).countInEconomy(true).build());
        line = estimateItemRepository.save(EstimateItem.builder()
                .estimate(estimate).type(ItemType.WORK).name("Робота").unit(Unit.M2)
                .quantity(new BigDecimal("100.000")).unitPrice(new BigDecimal("100.00"))
                .lineTotal(new BigDecimal("10000.00")).sortOrder(0).build());
    }

    // ---- B-78: the advance may not exceed what the act bills -------------------------------

    /**
     * The PDF prints {@code max(0, total + receipts − advance)}, so an advance bigger than the act
     * silently became «До сплати 0 ₴»: the client was told he owes nothing while 10 000 ₴ of the
     * prepayment was recorded against nothing at all.
     */
    @Test
    void anAdvanceBiggerThanTheActIsRefusedAtTheHeaderSave() {
        WorkActResponse act = createAct();
        setLine(act.id(), "20");          // 20 × 100 = 2 000 ₴

        assertThatThrownBy(() -> updateAdvance(act.id(), "3000.00"))
                .isInstanceOf(WorkActValidationException.class)
                .hasMessageContaining("advance-over-total");
    }

    @Test
    void anAdvanceWithinTheActIsAccepted_andReceiptsCountTowardsIt() {
        WorkActResponse act = createAct();
        setLine(act.id(), "20");          // 2 000 ₴ of work
        addReceipt(act.id(), "1500.00");  // + 1 500 ₴ of material

        WorkActResponse saved = updateAdvance(act.id(), "3000.00");

        assertThat(saved.advanceOffset()).isEqualByComparingTo("3000.00");
        assertThat(saved.payable()).isEqualByComparingTo("500.00");
    }

    /**
     * B-93. The portal signature skips the advance guard on purpose, so the doors that can still
     * shrink a SENT act must refuse: cut from 2 000 to 1 000 under a 1 500 advance, the client
     * signed «До сплати 0».
     */
    @Test
    void aSentActCannotBeShrunkUnderItsAdvance() {
        WorkActResponse act = createAct();
        setLine(act.id(), "20");          // 2 000 ₴
        updateAdvance(act.id(), "1500.00");
        jdbc.update("UPDATE work_act SET status = 'SENT', sent_at = now() WHERE id = ?", act.id());

        assertThatThrownBy(() -> setLine(act.id(), "10"))   // 1 000 ₴ < 1 500
                .isInstanceOf(WorkActValidationException.class)
                .hasMessageContaining("advance-over-total");
    }

    /** An omitted advance leaves the stored one alone — it used to clear it (B-78). */
    @Test
    void anOmittedAdvanceKeepsTheStoredOne() {
        WorkActResponse act = createAct();
        setLine(act.id(), "20");
        updateAdvance(act.id(), "1000.00");

        WorkActResponse saved = updateAdvance(act.id(), null);

        assertThat(saved.advanceOffset()).isEqualByComparingTo("1000.00");
    }

    // ---- B-79: one slip, one act -----------------------------------------------------------

    /**
     * The same fiscal identity on two acts was a warning on both and billed on both: the client
     * paid twice for one purchase. The identity is authoritative (V134: one {@code fn} + one
     * {@code id} is one piece of paper), so there is nothing to ask and no override to offer.
     */
    @Test
    void aSlipASignedActAlreadyBilledCannotBeSignedOnAnotherAct() throws Exception {
        WorkActResponse first = createAct();
        setLine(first.id(), "40");
        UUID firstReceipt = addReceipt(first.id(), "2400.00");
        identify(firstReceipt, "4000123456", "7777");
        workActService.signOffline(first.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        WorkActResponse second = createAct();
        setLine(second.id(), "40");
        UUID secondReceipt = addReceipt(second.id(), "2400.00");
        identify(secondReceipt, "4000123456", "7777");

        assertThatThrownBy(() -> workActService.signOffline(second.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId()))
                .hasMessageContaining("receipt-already-billed");
    }

    /** A different paper with its own code signs normally — the guard keys on the identity only. */
    @Test
    void aDifferentSlipIsNotAffected() throws Exception {
        WorkActResponse first = createAct();
        setLine(first.id(), "40");
        identify(addReceipt(first.id(), "2400.00"), "4000123456", "7777");
        workActService.signOffline(first.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        WorkActResponse second = createAct();
        setLine(second.id(), "40");
        identify(addReceipt(second.id(), "2400.00"), "4000123456", "8888");

        workActService.signOffline(second.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        assertThat(actRepository.findById(second.id()).orElseThrow().getDocHash()).isNotBlank();
    }

    // ---- B-80 / B-82: the database says it too ---------------------------------------------

    /** The optimistic-lock column V144 adds — a PATCH built on a stale read must not undo V134's
     *  {@code billed_on_act_id} stamp, and that needs a version, not a merge. */
    @Test
    void anActReceiptCarriesAVersionThatMovesOnEveryWrite() {
        WorkActResponse act = createAct();
        UUID receiptId = addReceipt(act.id(), "100.00");

        Long created = jdbc.queryForObject(
                "SELECT version FROM work_act_receipt WHERE id = ?", Long.class, receiptId);
        var receipt = receiptRepository.findById(receiptId).orElseThrow();
        receipt.setLabel("Чек з Епіцентру");
        receiptRepository.saveAndFlush(receipt);

        assertThat(created).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT version FROM work_act_receipt WHERE id = ?", Long.class, receiptId))
                .isEqualTo(1L);
    }

    /** B-82: two open acts on one object were accepted by the DB even though the service refuses
     *  them — a concurrent pair could land both. The partial index is the backstop. */
    @Test
    void theDatabaseRefusesASecondOpenActOnOneObject() {
        createAct();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO work_act (id, user_id, project_id, number, kind, status, issued_at,
                                      period_from, period_to, created_at, updated_at)
                VALUES (?, ?, ?, '999', 'INTERIM', 'DRAFT', current_date, current_date, current_date,
                        now(), now())
                """, UUID.randomUUID(), owner.getId(), project.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** …and a second SIGNED FINAL act, which is what closes an object for good (B-62). */
    @Test
    void theDatabaseRefusesASecondSignedFinalActOnOneObject() {
        jdbc.update("""
                INSERT INTO work_act (id, user_id, project_id, number, kind, status, issued_at,
                                      period_from, period_to, signed_at, created_at, updated_at)
                VALUES (?, ?, ?, '1', 'FINAL', 'SIGNED', current_date, current_date, current_date,
                        now(), now(), now())
                """, UUID.randomUUID(), owner.getId(), project.getId());

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO work_act (id, user_id, project_id, number, kind, status, issued_at,
                                      period_from, period_to, signed_at, created_at, updated_at)
                VALUES (?, ?, ?, '2', 'FINAL', 'SIGNED', current_date, current_date, current_date,
                        now(), now(), now())
                """, UUID.randomUUID(), owner.getId(), project.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private WorkActResponse createAct() {
        return workActService.create(project.getId(),
                new WorkActCreateRequest(WorkActKind.INTERIM, null, LocalDate.now(),
                        LocalDate.now().minusDays(7), LocalDate.now(), null, null, null, null, null,
                        null), owner.getId(), null);
    }

    private void setLine(UUID actId, String qty) {
        workActService.replaceItems(actId, new WorkActItemsRequest(List.of(
                new WorkActItemsRequest.Line(line.getId(), estimate.getId(), ItemType.WORK,
                        "Робота", null, Unit.M2, new BigDecimal("100.00"), new BigDecimal(qty)))),
                owner.getId());
    }

    private WorkActResponse updateAdvance(UUID actId, String advance) {
        return workActService.updateHeader(actId, new WorkActUpdateRequest(
                WorkActKind.INTERIM, null, LocalDate.now(), LocalDate.now().minusDays(7),
                LocalDate.now(), null, null, null, null, null, null, null,
                advance == null ? null : new BigDecimal(advance)), owner.getId());
    }

    /** A receipt row written directly: the service path needs a real photo upload, and none of
     *  these tests is about the photo. */
    private UUID addReceipt(UUID actId, String amount) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO work_act_receipt (id, work_act_id, label, amount, returned_amount,
                                              storage_key, sort_order, itemized, created_at, version)
                VALUES (?, ?, 'Чек', ?::numeric, 0, 'k/' || ?, 0, false, now(), 0)
                """, id, actId, amount, id);
        return id;
    }

    private void identify(UUID receiptId, String fn, String fiscalId) {
        jdbc.update("UPDATE work_act_receipt SET fiscal_fn = ?, fiscal_id = ? WHERE id = ?",
                fn, fiscalId, receiptId);
    }
}
