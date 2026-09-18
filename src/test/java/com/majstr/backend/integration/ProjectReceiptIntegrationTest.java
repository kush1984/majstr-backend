package com.majstr.backend.integration;

import com.majstr.backend.dto.ProjectReceiptRequest;
import com.majstr.backend.dto.ProjectReceiptsResponse;
import com.majstr.backend.repository.ProjectReceiptRepository;
import com.majstr.backend.service.ProjectReceiptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V129 — «чеки обʼєкта», and the two facts only a real database can answer.
 *
 * <p>The first is the CHECK that keeps the flag and the money honest: a receipt may not point at an
 * {@code object_expenses} row while it is still «клієнт відшкодовує». Without it the same paper
 * could be a receivable and a cost at once, and the object's economy would quietly count it twice.
 * The second is the {@code ON DELETE CASCADE} to the project — a deleted object must not leave
 * orphan receipts pointing at nothing.</p>
 *
 * <p>The flip itself is exercised through the service, so the expense that appears in the journal
 * is the one the master's tap actually creates.</p>
 */
class ProjectReceiptIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectReceiptService receiptService;
    @Autowired ProjectReceiptRepository receipts;

    private UUID ownerId;
    private UUID projectId;

    @BeforeEach
    void seed() {
        ownerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, email_verified)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, TRUE)
                """, ownerId, ownerId + "@t.ua", ownerId + "@t.ua", ownerId.toString().substring(0, 8));

        projectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?, ?, 'Квартира', 'вул. Тестова 1', 'IN_PROGRESS')
                """, projectId, ownerId);
    }

    @Test
    void aReimbursableReceiptMayNotAlsoCarryAnExpense() {
        UUID expenseId = insertExpense("999.00");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO project_receipt (id, project_id, label, amount, reimbursable, expense_id)
                VALUES (?, ?, 'Епіцентр', 483.50, TRUE, ?)
                """, UUID.randomUUID(), projectId, expenseId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Saved at the till: no amount yet, and above all no money anywhere in the economy. */
    @Test
    void aSavedReceiptIsAReceivableAndWritesNothingToTheJournal() {
        insertReceipt("Чек №1", "0.00");

        ProjectReceiptsResponse list = receiptService.list(projectId, ownerId);

        assertThat(list.items()).hasSize(1);
        assertThat(list.items().get(0).reimbursable()).isTrue();
        assertThat(list.unpricedCount()).isEqualTo(1);
        assertThat(expenseCount()).isZero();
    }

    @Test
    void flippingToOwnCostPostsAnExpenseAndFlippingBackRemovesIt() {
        UUID receiptId = insertReceipt("Епіцентр", "483.50");

        receiptService.update(projectId, receiptId, ownerId, new ProjectReceiptRequest(
                "Епіцентр", new BigDecimal("483.50"), LocalDate.of(2026, 9, 8), false, null, null));

        assertThat(expenseCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT amount FROM object_expenses WHERE object_id = ?", BigDecimal.class, projectId))
                .isEqualByComparingTo("483.50");
        assertThat(receiptService.list(projectId, ownerId).ownTotal()).isEqualByComparingTo("483.50");
        assertThat(receiptService.list(projectId, ownerId).reimbursableTotal()).isEqualByComparingTo("0.00");

        receiptService.update(projectId, receiptId, ownerId, new ProjectReceiptRequest(
                "Епіцентр", new BigDecimal("483.50"), LocalDate.of(2026, 9, 8), true, null, null));

        assertThat(expenseCount()).isZero();
        assertThat(receiptService.list(projectId, ownerId).reimbursableTotal())
                .isEqualByComparingTo("483.50");
    }

    @Test
    void deletingTheObjectTakesItsReceiptsWithIt() {
        insertReceipt("Епіцентр", "483.50");

        jdbc.update("DELETE FROM projects WHERE id = ?", projectId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM project_receipt WHERE project_id = ?", Long.class, projectId))
                .isZero();
    }

    /**
     * A BLANK fiscal code does not identify a paper (review item B-21). Legacy rows were stored as
     * {@code ''} and the {@code IS NOT NULL} lookup let them through, so every such receipt was the
     * twin of every other one — and at sign time the reconciler settled two unrelated papers against
     * each other. V136 nulled what was already there; the query refuses a blank regardless, because
     * that is the half that also covers a row written by an older client.
     */
    @Test
    void aBlankFiscalCodeIsNotAnIdentifiedReceipt() {
        UUID blank = insertReceipt("Без QR", "483.50");
        jdbc.update("UPDATE project_receipt SET fiscal_fn = '', fiscal_id = '   ' WHERE id = ?", blank);
        UUID identified = insertReceipt("Епіцентр", "1200.00");
        jdbc.update("UPDATE project_receipt SET fiscal_fn = '4000123456', fiscal_id = '77' WHERE id = ?",
                identified);

        assertThat(receipts.findIdentifiedByProjectId(projectId))
                .extracting(r -> r.getId())
                .containsExactly(identified);
    }

    /** The write path normalises too, so a client sending {@code ""} stores no identity at all. */
    @Test
    void anEmptyStringSentByAClientIsStoredAsNoIdentity() {
        UUID id = insertReceipt("Епіцентр", "483.50");

        receiptService.update(projectId, id, ownerId, new ProjectReceiptRequest(
                "Епіцентр", new BigDecimal("483.50"), LocalDate.of(2026, 9, 8), null, "", ""));

        assertThat(jdbc.queryForObject(
                "SELECT fiscal_fn FROM project_receipt WHERE id = ?", String.class, id)).isNull();
        assertThat(receipts.findIdentifiedByProjectId(projectId)).isEmpty();
    }

    // ---- helpers ----------------------------------------------------------

    private UUID insertReceipt(String label, String amount) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project_receipt (id, project_id, label, amount, storage_key, sort_order)
                VALUES (?, ?, ?, ?::numeric, 'object-receipts/x.jpg', 0)
                """, id, projectId, label, amount);
        return id;
    }

    private UUID insertExpense(String amount) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO object_expenses (id, object_id, category, source, amount, spent_at)
                VALUES (?, ?, 'MATERIALS', 'RECEIPT', ?::numeric, CURRENT_DATE)
                """, id, projectId, amount);
        return id;
    }

    private long expenseCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM object_expenses WHERE object_id = ?", Long.class, projectId);
    }
}
