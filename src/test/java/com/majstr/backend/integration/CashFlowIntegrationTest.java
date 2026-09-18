package com.majstr.backend.integration;

import com.majstr.backend.dto.CashEntryKind;
import com.majstr.backend.dto.CashEntryRequest;
import com.majstr.backend.dto.CashFlowResponse;
import com.majstr.backend.dto.ProjectReceiptRequest;
import com.majstr.backend.entity.CashCategory;
import com.majstr.backend.entity.CashDirection;
import com.majstr.backend.exception.ExpenseLinkedToReceiptException;
import com.majstr.backend.service.CashFlowService;
import com.majstr.backend.service.ProjectReceiptService;
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
 * «Мої гроші» (V135) against a real database — the parts no Mockito test can reach.
 *
 * <p>Three of them matter. The feed is the FIRST owner-wide money query in this codebase (everything
 * else reads {@code WHERE object_id = ?}), so the join through {@code projects.owner_id} is only
 * proved here. The object router really has to land a row in the object's own journal. And the
 * V129 ruling — a receipt the client pays back is a receivable, not a cost — has to survive into
 * this screen without anyone restating it.</p>
 */
class CashFlowIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired CashFlowService cashService;
    @Autowired ProjectReceiptService projectReceiptService;

    private UUID ownerId;
    private UUID projectId;

    @BeforeEach
    void seed() {
        ownerId = newOwner();
        projectId = newProject(ownerId, "Квартира на Лесі");
    }

    /** Money in, money out, and a row that belongs to no object — one movement. */
    @Test
    void theFeedUnionsObjectMoneyWithHisOwnRows() {
        LocalDate day = LocalDate.of(2026, 9, 10);
        insertReceipt("6000.00", day, false);
        insertExpense("1500.00", day);
        cashService.create(ownerId, new CashEntryRequest(CashDirection.EXPENSE,
                new BigDecimal("1200.00"), CashCategory.FUEL, "Дизель", day, false, null), null);

        CashFlowResponse flow = flow(day);

        assertThat(flow.entries()).extracting(CashFlowResponse.Entry::kind)
                .containsExactlyInAnyOrder(CashEntryKind.OBJECT_PAYMENT, CashEntryKind.OBJECT_EXPENSE,
                        CashEntryKind.PERSONAL);
        assertThat(flow.income()).isEqualByComparingTo("6000.00");
        assertThat(flow.expense()).isEqualByComparingTo("2700.00");
        assertThat(flow.earned()).isEqualByComparingTo("3300.00");
        // The object row is named, so the master knows where his money went without opening it.
        assertThat(flow.entries()).filteredOn(e -> e.kind() == CashEntryKind.OBJECT_EXPENSE)
                .singleElement()
                .satisfies(e -> assertThat(e.projectName()).isEqualTo("Квартира на Лесі"));
    }

    /** Another master's objects are not his money. The join through `projects.owner_id` is the only
     *  thing standing between the two, and it exists nowhere else in this codebase. */
    @Test
    void anotherMastersObjectsAreInvisible() {
        LocalDate day = LocalDate.of(2026, 9, 10);
        UUID stranger = newOwner();
        UUID hisProject = newProject(stranger, "Чужий обʼєкт");
        jdbc.update("""
                INSERT INTO payment_receipt (id, project_id, amount, received_at, label)
                VALUES (?, ?, 9999.00, ?, 'Оплата')
                """, UUID.randomUUID(), hisProject, day);
        jdbc.update("""
                INSERT INTO object_expenses (id, object_id, category, source, amount, spent_at)
                VALUES (?, ?, 'MATERIALS', 'MANUAL', 7777.00, ?)
                """, UUID.randomUUID(), hisProject, day);

        CashFlowResponse flow = flow(day);

        assertThat(flow.entries()).isEmpty();
        assertThat(flow.income()).isEqualByComparingTo("0.00");
        assertThat(flow.expense()).isEqualByComparingTo("0.00");
    }

    /**
     * Adding here writes ONLY his own book: money that belongs to an object is already in that
     * object's journal, so asking him to point at one would be asking for a second copy.
     */
    @Test
    void addingHereNeverWritesIntoAnObject() {
        LocalDate day = LocalDate.of(2026, 9, 12);

        CashFlowResponse.Entry saved = cashService.create(ownerId, new CashEntryRequest(
                CashDirection.INCOME, new BigDecimal("5000.00"), CashCategory.WORK,
                "За плитку", day, false, null), null);

        assertThat(saved.kind()).isEqualTo(CashEntryKind.PERSONAL);
        assertThat(count("SELECT count(*) FROM cash_entry WHERE owner_id = ?", ownerId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payment_receipt WHERE project_id = ?", projectId))
                .isZero();
        assertThat(count("SELECT count(*) FROM object_expenses WHERE object_id = ?", projectId))
                .isZero();
    }

    /**
     * An object's row is edited and deleted from HERE (master's ruling: «з можливістю видаляти рядки
     * чи едітати»). It is a second DOOR to one record, never a second copy — the write lands in the
     * object's own table, so its economy moves with it.
     */
    @Test
    void anObjectsOwnRowIsEditedAndDeletedInPlace() {
        LocalDate day = LocalDate.of(2026, 9, 12);
        insertReceipt("6000.00", day, false);
        insertExpense("1500.00", day);
        List<CashFlowResponse.Entry> rows = flow(day).entries();
        CashFlowResponse.Entry payment = rows.stream()
                .filter(e -> e.kind() == CashEntryKind.OBJECT_PAYMENT).findFirst().orElseThrow();
        CashFlowResponse.Entry spend = rows.stream()
                .filter(e -> e.kind() == CashEntryKind.OBJECT_EXPENSE).findFirst().orElseThrow();

        cashService.update(ownerId, payment.id(), new CashEntryRequest(CashDirection.INCOME,
                new BigDecimal("6500.00"), null, "Друга частина", day, false,
                CashEntryKind.OBJECT_PAYMENT));
        cashService.update(ownerId, spend.id(), new CashEntryRequest(CashDirection.EXPENSE,
                new BigDecimal("1800.00"), CashCategory.CREW, "Хлопцям", day, false,
                CashEntryKind.OBJECT_EXPENSE));

        // The object's OWN tables moved — this screen edits the record, not a copy of it.
        assertThat(jdbc.queryForObject(
                "SELECT amount FROM payment_receipt WHERE project_id = ?", BigDecimal.class, projectId))
                .isEqualByComparingTo("6500.00");
        assertThat(jdbc.queryForObject(
                "SELECT category FROM object_expenses WHERE object_id = ?", String.class, projectId))
                .isEqualTo("LABOR");
        assertThat(flow(day).earned()).isEqualByComparingTo("4700.00"); // 6 500 − 1 800

        cashService.delete(ownerId, payment.id(), CashEntryKind.OBJECT_PAYMENT);
        cashService.delete(ownerId, spend.id(), CashEntryKind.OBJECT_EXPENSE);

        assertThat(count("SELECT count(*) FROM payment_receipt WHERE project_id = ?", projectId))
                .isZero();
        assertThat(count("SELECT count(*) FROM object_expenses WHERE object_id = ?", projectId))
                .isZero();
    }

    /**
     * The one refusal that survives the new door: an expense a V129 till receipt owns. The two are
     * one fact — the receipt mirrors its amount onto the row — so editing it here would leave the
     * receipt claiming «це моя витрата» over a figure the journal no longer agrees with.
     */
    @Test
    void anExpenseOwnedByATillReceiptIsStillRefused() {
        LocalDate day = LocalDate.now();
        UUID receiptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project_receipt (id, project_id, label, amount, storage_key, sort_order)
                VALUES (?, ?, 'Епіцентр', 2000.00, 'object-receipts/x.jpg', 0)
                """, receiptId, projectId);
        projectReceiptService.update(projectId, receiptId, ownerId, new ProjectReceiptRequest(
                "Епіцентр", new BigDecimal("2000.00"), day, false, null, null));
        CashFlowResponse.Entry row = flow(day).entries().stream()
                .filter(e -> e.kind() == CashEntryKind.OBJECT_EXPENSE).findFirst().orElseThrow();

        assertThatThrownBy(() -> cashService.update(ownerId, row.id(), new CashEntryRequest(
                CashDirection.EXPENSE, new BigDecimal("99.00"), CashCategory.MATERIALS, null, day,
                false, CashEntryKind.OBJECT_EXPENSE)))
                .isInstanceOf(ExpenseLinkedToReceiptException.class);
    }

    /**
     * V129 ruled a till receipt the client pays back is a receivable, not a cost — so it writes no
     * {@code object_expenses} row. This screen inherits that for free by reading that table and
     * never {@code project_receipt}; flip the receipt to «моя витрата» and the cost appears.
     */
    @Test
    void aReceiptTheClientPaysBackIsNotASpend_untilTheMasterSaysItIsHis() {
        LocalDate day = LocalDate.now();
        UUID receiptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project_receipt (id, project_id, label, amount, storage_key, sort_order)
                VALUES (?, ?, 'Епіцентр', 2000.00, 'object-receipts/x.jpg', 0)
                """, receiptId, projectId);

        assertThat(flow(day).expense()).isEqualByComparingTo("0.00");

        projectReceiptService.update(projectId, receiptId, ownerId, new ProjectReceiptRequest(
                "Епіцентр", new BigDecimal("2000.00"), day, false, null, null));

        assertThat(flow(day).expense()).isEqualByComparingTo("2000.00");
    }

    /**
     * «Прийшло» counts the reimbursement — it really arrived. «Заробив» does not, or the month is
     * inflated by exactly the material the client paid back.
     *
     * <p>The flag is set by TICKING an object's payment from this screen, which is the only way it
     * can be set on one — so this also proves the edit door carries it into {@code payment_receipt}
     * and back out into the feed.</p>
     */
    @Test
    void tickingAnObjectPaymentAsARefundTakesItOutOfEarningsOnly() {
        LocalDate day = LocalDate.of(2026, 9, 14);
        insertReceipt("12000.00", day, false);
        insertReceipt("3000.00", day, false);
        CashFlowResponse.Entry refund = flow(day).entries().stream()
                .filter(e -> e.amount().compareTo(new BigDecimal("3000.00")) == 0)
                .findFirst().orElseThrow();

        cashService.update(ownerId, refund.id(), new CashEntryRequest(CashDirection.INCOME,
                new BigDecimal("3000.00"), null, "Повернули за плитку", day, true,
                CashEntryKind.OBJECT_PAYMENT));

        CashFlowResponse flow = flow(day);
        assertThat(flow.income()).isEqualByComparingTo("15000.00");
        assertThat(flow.refunds()).isEqualByComparingTo("3000.00");
        assertThat(flow.earned()).isEqualByComparingTo("12000.00");
        // …and the OBJECT's own «Отримано» is untouched by the flag — the standing constraint that
        // existing clients' figures must not shift under them.
        assertThat(jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM payment_receipt WHERE project_id = ?",
                BigDecimal.class, projectId)).isEqualByComparingTo("15000.00");
    }

    /** A refund is income. On spending it would mean nothing and would skew «Заробив» the other way. */
    @Test
    void theDatabaseRefusesARefundOnSpending() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO cash_entry (id, owner_id, direction, amount, happened_on, happened_at,
                                        material_refund)
                VALUES (?, ?, 'EXPENSE', 100.00, CURRENT_DATE, now(), TRUE)
                """, UUID.randomUUID(), ownerId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Deleting the master takes his own book with him; nothing owner-scoped may outlive him. */
    @Test
    void deletingTheMasterTakesHisOwnRowsWithHim() {
        cashService.create(ownerId, new CashEntryRequest(CashDirection.EXPENSE,
                new BigDecimal("300.00"), CashCategory.TOOLS, null, LocalDate.now(), false, null), null);

        jdbc.update("DELETE FROM users WHERE id = ?", ownerId);

        assertThat(count("SELECT count(*) FROM cash_entry WHERE owner_id = ?", ownerId)).isZero();
    }

    /**
     * The period bounds are INCLUSIVE and resolved in {@code Europe/Kyiv}. The last day of a month is
     * the one the master reads as the last day — a UTC boundary would drop it for the first hours of
     * the next month, which is exactly when he looks at the month just finished.
     */
    @Test
    void theLastDayOfTheMonthIsInsideTheMonth() {
        LocalDate last = LocalDate.of(2026, 9, 30);
        insertReceipt("1000.00", last, false);

        CashFlowResponse september = cashService.flow(ownerId,
                LocalDate.of(2026, 9, 1), last, false);

        assertThat(september.income()).isEqualByComparingTo("1000.00");
        assertThat(september.to()).isEqualTo(last);
    }

    // ---- helpers ----------------------------------------------------------

    private CashFlowResponse flow(LocalDate day) {
        return cashService.flow(ownerId, day.minusDays(5), day.plusDays(5), false);
    }

    private UUID newOwner() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, email_verified)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, TRUE)
                """, id, id + "@t.ua", id + "@t.ua", id.toString().substring(0, 8));
        return id;
    }

    private UUID newProject(UUID owner, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?, ?, ?, 'вул. Тестова 1', 'IN_PROGRESS')
                """, id, owner, name);
        return id;
    }

    private void insertReceipt(String amount, LocalDate day, boolean refund) {
        jdbc.update("""
                INSERT INTO payment_receipt (id, project_id, amount, received_at, label, material_refund)
                VALUES (?, ?, ?::numeric, ?, 'Оплата', ?)
                """, UUID.randomUUID(), projectId, amount, day, refund);
    }

    private void insertExpense(String amount, LocalDate day) {
        jdbc.update("""
                INSERT INTO object_expenses (id, object_id, category, source, amount, spent_at)
                VALUES (?, ?, 'MATERIALS', 'MANUAL', ?::numeric, ?)
                """, UUID.randomUUID(), projectId, amount, day);
    }

    private long count(String sql, UUID arg) {
        return jdbc.queryForObject(sql, Long.class, arg);
    }
}
