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
 * proved here. The object router really has to land a row in the object's own journal. And the five
 * sources have to be DISJOINT (review B-33): the same material must not be able to reach the
 * expense side both as an {@code object_expenses} row and as the receipt that created it — which
 * is decided by two {@code @Query} strings no Mockito test can see.</p>
 */
class CashFlowIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired CashFlowService cashService;
    @Autowired ProjectReceiptService projectReceiptService;

    private UUID ownerId;
    private UUID projectId;
    private int actNumber = 1;

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
     * The V129 ruling — a till receipt the client pays back is a RECEIVABLE, not a cost — governs
     * the OBJECT's economy and is untouched. On THIS screen the same paper is 2 000 ₴ that left his
     * pocket (review B-33), so it is spending either way; what may never happen is it counting
     * TWICE when he flips it to «моя витрата» and the flip writes an {@code object_expenses} row.
     *
     * <p>That disjointness lives entirely in the {@code reimbursable = true} filter of
     * {@code findOutOfPocketByOwnerAndPeriod}, which is exactly what a real database proves.</p>
     */
    @Test
    void aTillReceiptIsSpendingWhoeverPaysForIt_andNeverTwice() {
        LocalDate day = LocalDate.now();
        UUID receiptId = UUID.randomUUID();
        insertTillReceipt(receiptId, "2000.00");

        CashFlowResponse asReceivable = flow(day);
        assertThat(asReceivable.expense()).isEqualByComparingTo("2000.00");
        assertThat(asReceivable.entries()).extracting(CashFlowResponse.Entry::kind)
                .containsExactly(CashEntryKind.OBJECT_RECEIPT);
        assertThat(count("SELECT count(*) FROM object_expenses WHERE object_id = ?", projectId))
                .isZero();

        projectReceiptService.update(projectId, receiptId, ownerId, new ProjectReceiptRequest(
                "Епіцентр", new BigDecimal("2000.00"), day, false, null, null));

        CashFlowResponse asOwnCost = flow(day);
        assertThat(asOwnCost.expense()).isEqualByComparingTo("2000.00");
        assertThat(asOwnCost.entries()).extracting(CashFlowResponse.Entry::kind)
                .containsExactly(CashEntryKind.OBJECT_EXPENSE);
    }

    /**
     * Signing an act with {@code receipts_to_expenses} ON posts the receipt as an
     * {@code object_expenses} row and stamps the object's twin {@code billed_on_act_id}. Both rows
     * describe ONE purchase, so the feed must show one — and the one it shows is the expense,
     * because that is the record every other screen already reads.
     */
    @Test
    void aTillReceiptBilledOnAnActThatPostsExpensesIsCountedOnce() {
        LocalDate day = LocalDate.now();
        UUID receiptId = UUID.randomUUID();
        UUID actId = insertSignedAct(true);
        insertTillReceipt(receiptId, "2000.00");
        jdbc.update("UPDATE project_receipt SET billed_on_act_id = ? WHERE id = ?", actId, receiptId);
        insertActReceipt(actId, "2000.00", "0.00");
        insertExpense("2000.00", day);

        CashFlowResponse flow = flow(day);

        assertThat(flow.expense()).isEqualByComparingTo("2000.00");
        assertThat(flow.entries()).extracting(CashFlowResponse.Entry::kind)
                .containsExactly(CashEntryKind.OBJECT_EXPENSE);
    }

    /**
     * With {@code receipts_to_expenses} OFF nothing posts an expense, so the act's own receipt is
     * the ONLY record that the master paid for that material — and the client's payment for it is
     * already sitting in «Прийшло» as part of the act. Miss it and the month reads 2 000 ₴ richer
     * than the till did.
     */
    @Test
    void anActReceiptIsTheOnlyRecordWhenTheActDoesNotPostExpenses() {
        LocalDate day = LocalDate.now();
        UUID actId = insertSignedAct(false);
        insertActReceipt(actId, "2000.00", "500.00");

        CashFlowResponse flow = flow(day);

        // 2 000 paid at the till, 500 handed back to the shop: he is out 1 500 (V115).
        assertThat(flow.expense()).isEqualByComparingTo("1500.00");
        assertThat(flow.entries()).extracting(CashFlowResponse.Entry::kind)
                .containsExactly(CashEntryKind.ACT_RECEIPT);
        // Frozen inside the act's `doc_hash` — the feed shows it and refuses to rewrite it.
        assertThat(flow.entries().getFirst().readOnly()).isTrue();
    }

    /**
     * The same paper in both tables (V134): the object receipt was reconciled onto the act, so the
     * act's copy and the object's copy are one purchase. The NOT EXISTS twin check is what keeps
     * the feed from billing the master for it twice.
     */
    @Test
    void aReconciledPairOfReceiptsIsOnePurchase() {
        LocalDate day = LocalDate.now();
        UUID actId = insertSignedAct(false);
        UUID receiptId = UUID.randomUUID();
        insertTillReceipt(receiptId, "2000.00");
        jdbc.update("""
                UPDATE project_receipt SET billed_on_act_id = ?, fiscal_fn = '4000', fiscal_id = '77'
                WHERE id = ?
                """, actId, receiptId);
        jdbc.update("""
                INSERT INTO work_act_receipt (id, work_act_id, label, amount, returned_amount,
                                              issued_at, fiscal_fn, fiscal_id, sort_order, created_at)
                VALUES (?, ?, 'Епіцентр', 2000.00, 0.00, CURRENT_DATE, '4000', '77', 0, now())
                """, UUID.randomUUID(), actId);

        CashFlowResponse flow = flow(day);

        assertThat(flow.expense()).isEqualByComparingTo("2000.00");
        assertThat(flow.entries()).extracting(CashFlowResponse.Entry::kind)
                .containsExactly(CashEntryKind.OBJECT_RECEIPT);
    }

    /** A DRAFT act bills nobody yet; its receipts belong to the act editor, not to a month's cash. */
    @Test
    void anUnsignedActsReceiptsAreNotCashYet() {
        UUID actId = insertAct("DRAFT", false);
        insertActReceipt(actId, "2000.00", "0.00");

        assertThat(flow(LocalDate.now()).expense()).isEqualByComparingTo("0.00");
    }

    /**
     * The refund tick LABELS income, and since B-33 it subtracts nothing: the purchase it repays is
     * on the expense side itself, and taking both charged the master for the same material twice.
     *
     * <p>The flag is set by TICKING an object's payment from this screen, which is the only way it
     * can be set on one — so this also proves the edit door carries it into {@code payment_receipt}
     * and back out into the feed.</p>
     */
    @Test
    void tickingAnObjectPaymentAsARefundLabelsItAndNothingMore() {
        LocalDate day = LocalDate.now();
        insertReceipt("12000.00", day, false);
        insertReceipt("3000.00", day, false);
        insertTillReceipt(UUID.randomUUID(), "3000.00");
        CashFlowResponse.Entry refund = flow(day).entries().stream()
                .filter(e -> e.amount().compareTo(new BigDecimal("3000.00")) == 0
                        && e.kind() == CashEntryKind.OBJECT_PAYMENT)
                .findFirst().orElseThrow();

        cashService.update(ownerId, refund.id(), new CashEntryRequest(CashDirection.INCOME,
                new BigDecimal("3000.00"), null, "Повернули за плитку", day, true,
                CashEntryKind.OBJECT_PAYMENT));

        CashFlowResponse flow = flow(day);
        assertThat(flow.income()).isEqualByComparingTo("15000.00");
        assertThat(flow.refunds()).isEqualByComparingTo("3000.00");
        // The 3 000 came back and the 3 000 went out: 12 000 of work is what he earned.
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

    private void insertTillReceipt(UUID id, String amount) {
        jdbc.update("""
                INSERT INTO project_receipt (id, project_id, label, amount, issued_at, storage_key,
                                             sort_order)
                VALUES (?, ?, 'Епіцентр', ?::numeric, CURRENT_DATE, 'object-receipts/x.jpg', 0)
                """, id, projectId, amount);
    }

    private UUID insertSignedAct(boolean receiptsToExpenses) {
        return insertAct("SIGNED", receiptsToExpenses);
    }

    private UUID insertAct(String status, boolean receiptsToExpenses) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO work_act (id, user_id, project_id, number, kind, status, issued_at,
                                      period_from, period_to, receipts_to_expenses,
                                      created_at, updated_at)
                VALUES (?, ?, ?, ?, 'INTERIM', ?, CURRENT_DATE, CURRENT_DATE, CURRENT_DATE, ?,
                        now(), now())
                """, id, ownerId, projectId, "A" + actNumber++, status, receiptsToExpenses);
        return id;
    }

    private void insertActReceipt(UUID actId, String amount, String returned) {
        jdbc.update("""
                INSERT INTO work_act_receipt (id, work_act_id, label, amount, returned_amount,
                                              issued_at, sort_order, created_at)
                VALUES (?, ?, 'Епіцентр', ?::numeric, ?::numeric, CURRENT_DATE, 0, now())
                """, UUID.randomUUID(), actId, amount, returned);
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
